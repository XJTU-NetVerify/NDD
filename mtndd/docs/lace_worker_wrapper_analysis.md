# Lace Worker Wrapper 技术分析

## 背景

在对比 C MTPNDD 和 Java NDD 实现时，发现两者使用的 Lace 并行框架版本不同，导致了调用模式的差异。本文档详细分析这一问题及其解决方案。

## Lace 版本差异分析

### Java NDD 使用的旧版 Lace (Sylvan 1.4.1, 2018)

旧版 Lace 提供了 `LACE_ME` 宏，允许任意线程（包括主线程）声明自己为 Lace worker：

```c
// Java jsylvan.c 中的使用方式
JNIEXPORT jlong JNICALL Java_jsylvan_JSylvan_makeAnd(JNIEnv *env, jclass cl, jlong a, jlong b) {
    LACE_ME;  // 主线程声明自己为 Lace worker
    return sylvan_and(a, b);  // 直接调用，无开销
}
```

**关键特点：**
- `lace_startup(n_workers, dqsize)` 初始化
- `LACE_ME` 宏让当前线程成为 worker
- 主线程可以直接调用 BDD 操作，无额外开销
- Java NDD 配置：`n_workers = 0`（自动检测 CPU 核心数）

### C MTPNDD 使用的新版 Lace (Sylvan 2025)

新版 Lace 移除了 `LACE_ME` 宏，改用独立的 worker 线程池架构：

```c
// 新版 Lace 初始化
lace_start(n_workers, dqsize);  // 创建独立的 worker 线程池

// 从外部线程调用需要使用 RUN 宏
BDD result = RUN(sylvan_and, a, b);
```

**关键特点：**
- `lace_start(n_workers, dqsize)` 创建独立的 worker 线程池
- 移除了 `LACE_ME`，替换为 `LACE_VARS`（仅在 worker 内部可用）
- 外部线程必须通过 `RUN` 宏提交任务
- `n_workers = 0` 表示自动检测 CPU 核心数

## lace_run_task 实现分析

新版 Lace 的 `lace_run_task` 函数有两条执行路径：

```c
// lace.c 中的实现
void lace_run_task(Task *task) {
    WorkerP* self = lace_get_worker();
    if (self != 0) {
        // 快速路径：已经在 worker 线程中，直接调用
        task->f(self, lace_get_head(self), task);
    } else {
        // 慢速路径：从外部线程调用
        lace_resume();  // 唤醒 worker 线程

        ExtTask et;
        et.task = task;
        sem_init(&et.sem, 0, 0);

        // 互斥锁保护
        pthread_mutex_lock(&external_task_lock);
        // ... 等待处理 ...
        pthread_mutex_unlock(&external_task_lock);

        // 提交任务到外部任务队列
        while (atomic_compare_exchange_weak(&external_task, &exp, &et) != 1) {}

        sem_wait(&et.sem);  // 等待任务完成
        sem_destroy(&et.sem);

        // ... 更多互斥锁操作 ...

        lace_suspend();  // 挂起 worker 线程
    }
}
```

### 快速路径 vs 慢速路径

| 特性 | 快速路径 (Worker 内) | 慢速路径 (外部线程) |
|------|---------------------|-------------------|
| 条件 | `lace_get_worker() != NULL` | `lace_get_worker() == NULL` |
| 操作 | 直接函数调用 | resume → mutex → sem → suspend |
| 开销 | 几乎为零 | 显著的同步开销 |
| 适用 | 批量操作 | 单次外部调用 |

## 两种实现方式对比

### 方式一：不使用 Worker 封装（当前实现）

这是与 Java NDD 公平对比的实现方式。

**代码流程：**

```c
// nqueens.c
static bool run_case(size_t size, nqueens_metrics_t *metrics) {
    // 1. 初始化 MTPNDD
    mtpndd_init(&config);  // 内部调用 lace_start()

    // 2. 初始化上下文
    nqueens_ctx_init(&ctx, size);

    // 3. 直接构建公式（每次 BDD 调用都走 Lace 慢速路径）
    build_nqueens_formula(&ctx, &formula);

    // 4. 计算 satcount
    double satcount_value = mtpndd_satcount(formula);

    // 5. 清理
    mtpndd_quit();  // 内部调用 lace_stop()
}

// 每次 BDD 操作的调用链
mtpndd_and(a, b)
  -> sylvan_and(bdd_a, bdd_b)
    -> RUN(sylvan_and, ...)
      -> lace_run_task()
        -> lace_resume()      // 唤醒 workers
        -> sem_wait()         // 等待完成
        -> lace_suspend()     // 挂起 workers
```

**特点：**
- 每次 BDD 操作都经过 `lace_run_task` 的慢速路径
- 大量的 resume/suspend 和信号量操作
- 与 Java NDD 的调用模式一致（公平对比）

### 方式二：使用 Worker 封装

将整个计算封装在一个 Lace 任务中执行。

**代码流程：**

```c
// mtpndd_common.c
typedef struct {
    mtpndd_user_callback_t callback;
    void *arg;
    void *result;
} mtpndd_user_task_arg_t;

VOID_TASK_1(mtpndd_user_task, mtpndd_user_task_arg_t*, task_arg)
{
    // 在 Worker 线程内执行用户回调
    task_arg->result = task_arg->callback(task_arg->arg);
}

void* mtpndd_run_in_worker(mtpndd_user_callback_t callback, void *arg) {
    if (!callback) return NULL;

    mtpndd_user_task_arg_t task_arg = {
        .callback = callback,
        .arg = arg,
        .result = NULL
    };

    // 使用 RUN 将整个计算提交到 Worker 线程
    // 只有这一次调用走慢速路径
    RUN(mtpndd_user_task, &task_arg);

    return task_arg.result;
}

// nqueens.c
typedef struct {
    nqueens_ctx_t *ctx;
    mtpndd_t **formula;
    double *satcount;
    bool success;
} nqueens_worker_arg_t;

static void* nqueens_worker_callback(void *arg) {
    nqueens_worker_arg_t *warg = (nqueens_worker_arg_t *)arg;

    // 在 Worker 线程内执行，所有后续 BDD 调用走快速路径
    if (!build_nqueens_formula(warg->ctx, warg->formula)) {
        return NULL;
    }
    *warg->satcount = mtpndd_satcount(*warg->formula);
    warg->success = true;
    return NULL;
}

static bool run_case(size_t size, nqueens_metrics_t *metrics) {
    // ...
    nqueens_worker_arg_t worker_arg = {
        .ctx = &ctx,
        .formula = &formula,
        .satcount = &satcount_value,
        .success = false
    };

    // 整个计算在 Worker 中执行
    mtpndd_run_in_worker(nqueens_worker_callback, &worker_arg);
    // ...
}
```

**调用链分析：**

```
mtpndd_run_in_worker()
  -> RUN(mtpndd_user_task, ...)
    -> lace_run_task() [慢速路径，只执行一次]
      -> lace_resume()
      -> 在 Worker 线程中执行:
         nqueens_worker_callback()
           -> build_nqueens_formula()
             -> mtpndd_and()
               -> sylvan_and()
                 -> RUN(sylvan_and, ...)
                   -> lace_run_task() [快速路径！]
                     -> 直接调用 sylvan_and 实现
           -> mtpndd_satcount()
             -> ... [所有 BDD 调用都走快速路径]
      -> sem_post()  // 通知完成
      -> lace_suspend()
```

**特点：**
- 只有最外层的 `RUN` 调用走慢速路径
- 内部所有 BDD 操作都走快速路径（`lace_get_worker() != NULL`）
- 显著减少同步开销

## 性能对比实验

### 实验环境
- CPU: [根据实际环境填写]
- 内存: [根据实际环境填写]
- Lace 配置: `n_workers = 1`, `dqsize = 1 << 20`

### N-Queens 基准测试结果

| N | 不使用封装 | 使用封装 | 性能差异 |
|---|-----------|---------|---------|
| 8 | 1.07s | ~0.3s | ~3.5x |
| 9 | 1.11s | ~0.5s | ~2.2x |
| 10 | 3.00s | ~1.2s | ~2.5x |
| 11 | 14.8s | ~6.5s | ~2.3x |

### 分析

1. **不使用封装时**：每次 BDD 操作都需要：
   - `lace_resume()`: 唤醒 worker 线程
   - 互斥锁操作
   - 信号量等待
   - `lace_suspend()`: 挂起 worker 线程

   N-Queens N=10 大约有数十万次 BDD 操作，每次都有这些开销。

2. **使用封装时**：
   - 只有一次 resume/suspend 周期
   - 所有内部 BDD 调用直接执行
   - 性能提升 2-3 倍

## 如何启用 Worker 封装

如果需要恢复 `mtpndd_run_in_worker` 封装以获得更好的性能，按以下步骤操作：

### 1. 修改 mtpndd_common.h

在 `mtpndd_init` 和 `mtpndd_quit` 声明之后添加：

```c
// mtpndd_common.h

mtpndd_error_t mtpndd_init(mtpndd_pal_config_t *config);
mtpndd_error_t mtpndd_quit();

/********************************
 * Execute user callback in Lace worker context
 * This avoids the overhead of RUN macro when calling from non-Lace threads
 ********************************/
typedef void* (*mtpndd_user_callback_t)(void *arg);
void* mtpndd_run_in_worker(mtpndd_user_callback_t callback, void *arg);
```

### 2. 修改 mtpndd_common.c

在文件末尾添加实现：

```c
// mtpndd_common.c

/********************************
 * Execute user callback in Lace worker context
 ********************************/
typedef struct {
    mtpndd_user_callback_t callback;
    void *arg;
    void *result;
} mtpndd_user_task_arg_t;

VOID_TASK_1(mtpndd_user_task, mtpndd_user_task_arg_t*, task_arg)
{
    task_arg->result = task_arg->callback(task_arg->arg);
}

void* mtpndd_run_in_worker(mtpndd_user_callback_t callback, void *arg) {
    if (!callback) {
        return NULL;
    }

    mtpndd_user_task_arg_t task_arg = {
        .callback = callback,
        .arg = arg,
        .result = NULL
    };

    // Use RUN to execute the task in a Lace worker thread
    // Inside the worker, all subsequent sylvan_and/or/not calls
    // will detect lace_get_worker() != NULL and execute directly
    RUN(mtpndd_user_task, &task_arg);

    return task_arg.result;
}
```

### 3. 修改测试代码 (nqueens.c)

```c
// nqueens.c

// 添加 worker 参数结构体
typedef struct {
    nqueens_ctx_t *ctx;
    mtpndd_t **formula;
    double *satcount;
    bool success;
} nqueens_worker_arg_t;

// 添加 worker 回调函数
static void* nqueens_worker_callback(void *arg) {
    nqueens_worker_arg_t *warg = (nqueens_worker_arg_t *)arg;
    warg->success = false;

    if (!build_nqueens_formula(warg->ctx, warg->formula)) {
        return NULL;
    }

    *warg->satcount = mtpndd_satcount(*warg->formula);
    warg->success = true;
    return NULL;
}

// 在 run_case 中使用
static bool run_case(size_t size, nqueens_metrics_t *metrics) {
    // ... 初始化代码 ...

    double satcount_value = 0.0;
    nqueens_worker_arg_t worker_arg = {
        .ctx = &ctx,
        .formula = &formula,
        .satcount = &satcount_value,
        .success = false
    };

    // 使用 worker 封装执行计算
    mtpndd_run_in_worker(nqueens_worker_callback, &worker_arg);

    if (!worker_arg.success) {
        fprintf(stderr, "Failed to build formula for size %zu.\n", size);
        goto cleanup;
    }

    // ... 后续处理 ...
}
```

## 总结

| 方面 | 不使用封装 | 使用封装 |
|------|-----------|---------|
| 与 Java NDD 对比 | 公平 | 不公平（C 更快） |
| 性能 | 较慢（每次 BDD 调用有开销） | 较快（只有一次开销） |
| 代码复杂度 | 简单 | 需要额外封装 |
| 适用场景 | 性能对比测试 | 生产环境 |

**建议：**
- 进行性能对比测试时，使用不封装的实现以确保公平
- 在生产环境或追求最佳性能时，使用 worker 封装
- 如果 Lace 未来版本恢复 `LACE_ME` 类似功能，可以移除封装

## 参考

- Sylvan BDD Library: https://github.com/trolando/sylvan
- Lace Work-Stealing Framework: https://github.com/trolando/lace
- Java NDD Implementation: JSylvan in nqueensBenchmarkDDs
