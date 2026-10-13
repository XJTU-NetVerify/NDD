# 并行 NDD 核心优化：固定 SRE，只比较 native 的多核扩展

本轮遵循“优化自身单 worker / 六 workers 加速比”的要求。没有新增 Java 控制组，没有修改 SRE 的路由算法、调用组织、输入编码或 GC 策略。所有性能运行使用上一轮冻结的 SRE classes 与 NDD JAR，仅替换兼容的 JNI / C 核心及其并行开关。

## 固定条件与审计

F 全 BCDD，Header-Link、plan=1、原 trim 和字段/变量顺序，MF=3。CPU 0,2,4,6,8,10，JVM 8 GiB，native 图预算 3 GiB，RSS 守卫 12 GiB，超时 900 秒。08 缓存 4194304，12 缓存 16777216；水位固定 1228 MiB。已有 SRE 宏观 batch 全部保持开启，块大小 64。

`benchmark_core_scaling.py` 随机交错 native 构建和 worker 数，每配置两个独立进程，记录完整命令、classes / JAR / JNI 哈希、输入和源码快照。每个阶段 `checks.json` 检查冻结 Java classes 未改变，以及路由数、字段布局、链接顺序/极性、条件指纹、完整 RIB 文本及数值计数、reachability CSV 与上一轮结果一致。主时间使用包含初始化和 RIB 输出的应用 `total`；进程 wall、RSS、各次范围单独保留。

探针 `profile08` / `profile12` 各只有一次 1 / 6 workers 运行，带诊断开销，不能混入正式计时均值。其他阶段各配置重复两次。不同阶段之间没有完全随机交错，不能把跨阶段小差异归因于单个开关。

## 定位

新增 `PNDD_PAR_PROFILE`，按每 1024 次锁操作采样一次，区分缓存读取、缓存写入、NDD 规范表和 plain 标签规范表。另记录每个 worker 的完整优先级计算次数与时间。该选项默认关闭，普通构建不包含计时/计数分支。

六线程 12/MF3 探针有约 6.05 亿次共享缓存加锁读取、4.07 亿次写入、3913 万次 NDD 规范表加锁。读取采样的竞争事件为 16849 / 约 59 万次，实际采样等待和为 0.01813 秒；不能直接将其当作应用 wall。均匀采样的等待估算还会受抢占与长尾影响。采样未包括立即获得锁时的缓存行迁移成本。

各 worker 的优先级计算累计时间约 16.05–16.47 秒，08 约 0.61–0.67 秒，暂未发现这一层的大幅负载偏斜。该时间包含等待和嵌套工作，不能相加后直接当作串行占比或有效 CPU 工作。

## 通用核心实现

- **`PNDD_OPTIMISTIC_CACHE`**：共享操作缓存使用原子 payload 和版本发布，读取不写锁缓存行；写入逐项 CAS，失败直接放弃缓存写入。完整 key / operands / 版本一致时才命中，不接受混合条目。每项仍是 32 字节，容量和预算不增加。版本用尽停止替换，直到协作停顿清缓存，避免依赖版本回绕的假设。
- **`PNDD_PENDING_PARTITION`**：对已做 terminal / cache 过滤的 NDD 子任务，移动完整的 label / operands / result 记录，只划分未解决的部分。父任务原有根保护覆盖完整数组，归并保持集合语义。
- **`PNDD_BATCH_GRAIN`**：跟踪独立 batch 叶块的剩余数。粗任务足够覆盖 worker 时限制内部细任务，尾部恢复内部递归并行。统计的是叶块，避免把一个尚有许多元素的最后块误认为很多可窃取任务。结果引用仍在 JOIN 后由父任务取得，提示计数不参与正确性或 GC 生命周期。
- **`PNDD_LABEL_GRAIN`**：BDD / BCDD / ZDD 标签递归只在两个分支均有未缓存工作时 SPAWN，省去单一昂贵分支或缓存命中分支的任务。BCDD 提前得到的 cached high 结果在 low 递归期间额外保护，防止 GC 回收临时结果。BITSET 继续直接位运算。
- **`PNDD_ROOT_AFFINITY`**：适配 build-local Lace，由 worker 0 接收串行 admission 的 external RUN，其余 worker 照常窃取内部任务。协调 worker 保持协作轮询，保留 newframe / GC / quit 协议；没有借用 Java 线程的 TLS，也没有增加 worker。它可能增加空闲 CPU 消耗，需结合进程 CPU 时间判断收益。

这些能力均不识别 SRE、BGP、prefix 或网络名称。只有通用 NDD / 标签操作、batch 描述和 Lace 调度状态。

缓存同步使用原子 payload、发布版本和 fence。读到并发 writer 的任何新 payload 时，writer 的 BUSY 标记必须先于第二次版本读取；版本不一致即按 miss 处理。依据 [C11 委员会草案 N1570，7.17.4](https://www.open-std.org/jtc1/sc22/wg14/www/docs/n1570.pdf) 的 fence 同步规则；没有照搬依赖普通非原子 payload 的缓存实现。

## 试验记录

各阶段的 `summary.json` 保留平均值和范围，`runs.jsonl` / `runs.csv` 保留每次测量。阶段分别为缓存原型 (`cache08` / `cache12`)、粗细任务协调 (`grain08`)、标签任务过滤 (`label08` / `label12`)、固定协调 worker (`affinity08` / `affinity12`)。后续确认阶段会归档最终选择；没有稳定收益的开关不作为默认配置。

早期缓存原型对 12/MF3 的均值是 110.668 / 62.163 秒，自身 1.780×：主要改善绝对耗时，未明显突破扩展性。08 的粗细任务协调把任务数从约 44.4 万降到 31.2 万，时间改善仅约 2%，因此任务数减少不等同于并行成功。

最终交错确认采用 **缓存＋固定协调 worker**，其他三个任务粒度原型关闭，标签任务也关闭。没有靠增加缓存、调整水位或改变 SRE 分组取得结果。

| 输入 | 旧 1 worker s | 旧 6 workers s | 旧自身加速 | 新 1 worker s | 新 6 workers s | 新自身加速 | 六线程改善 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 08/MF3 | 7.823 | 6.133 | 1.276× | 7.628 | 4.950 | 1.541× | 1.239× |
| 12/MF3 | 125.120 | 68.871 | 1.817× | 125.345 | 61.862 | 2.026× | 1.113× |

新六线程 12/MF3 两次为 60.909 / 62.815 秒，旧六线程为 66.997 / 70.745 秒。单线程变化约 +0.18%，未取得额外串行改善；并行加速来自六线程实际变快。08 单线程约快 2.5%，六线程约快 19.3%（耗时下降百分比；速度倍率为 1.239×）。两次样本不能建立细小差异的统计显著性，但本轮观测范围内六线程旧/新没有重叠。

| 12/MF3 阶段均值 s / RSS | 旧 1 | 旧 6 | 新 1 | 新 6 |
|---|---:|---:|---:|---:|
| SRC 计算 | 93.374 | 44.432 | 94.064 | 39.918 |
| 转发 | 6.659 | 7.233 | 6.583 | 7.010 |
| 可达性 | 23.001 | 15.024 | 22.889 | 13.042 |
| 峰值 RSS MiB | 3311.5 | 3110.9 | 3127.5 | 3402.8 |

容量不变不代表 RSS 一定降低；新六线程峰值 RSS 均值略高，仍低于同一守卫。协调 worker 的轮询会占用空闲 CPU；这次结果证明当前负载的时延收益，没有证明它最省电或适合低频请求服务。

本轮共 **56 个正式计时进程＋4 个诊断进程**，正式计时审计通过：[checks-all.json](checks-all.json)、[全部明细](runs-all.csv)、[阶段摘要](summary-all.json)。最终同轮对照在 [confirm](confirm/summary.json)，完整表在 [tables.md](tables.md)。GC / Label GC 次数、任务数、缓存统计及进程 CPU 时间都保留，没有仅挑选速度最快的单次运行。

默认启用 `PNDD_OPTIMISTIC_CACHE` 与 `PNDD_ROOT_AFFINITY`。`PNDD_BATCH_GRAIN`、`PNDD_PENDING_PARTITION`、`PNDD_LABEL_GRAIN` 保留为实验开关，默认关闭：减少任务数的效果明确，稳定的额外吞吐收益还不充分。原有 `labels=false` 默认保持不变。

2.03× 仍低于 N-Queens 的约 3×，这次只推进了通用核心的缓存同步与调度局部性；没有完成所有并行优化。下一轮优先测量各 RUN 的工作大小和 worker 实际有效工作，区分短同步操作、标签计算、共享访存及 GC 的成本，再决定是否需要新的可并行核心操作。

## 正确性与使用

各原型与最终默认构建的五项 CTest 全部通过，包含四后端 / 混合字段、1/2/4/6 workers、枚举 oracle、生命周期、GC / 扩容 / 标签表耗尽、整批预算不足和 N-Queens。新增六 workers / 64 项共享缓存的碰撞检查；最终构建还通过六 workers 固定到两个 CPU 的同一套检查。

NDD JUnit 36 项、SRE JUnit 38 项均全部执行，零失败、零跳过。Debug ASan / UBSan 使用 1 / 6 workers、64 项碰撞缓存，开启 leak detection / UB halt，所有实验粒度选项也开启；全部通过。编译和检查日志保存在本目录。

最终默认核心另测原编码、BCDD、固定 left-fold 的 C N-Queens，每配置两次：

| N | 单 worker 计算 s | 六 workers 计算 s | 自身加速 | 解数 |
|---|---:|---:|---:|---:|
| 12 | 3.445 | 0.978 | 3.524× | 14200 |
| 13 | 25.337 | 7.113 | 3.562× | 73712 |

缓存 262144、图预算 3072 MiB、标签任务关闭，计时不含初始化/关闭。没有 GC，每次一次 external RUN。这里只验证新核心的多核扩展与解数，不将跨阶段历史秒数写成同轮 N-Queens 旧/新比较。[原始数据与构建哈希](nqueens/metadata.json)、[摘要](nqueens/summary.json)。

正式确认计时使用 `native/build-par-root` 的冻结构建。`native/build-par-final` 的最终默认构建在相同算法上补充了“超额 worker 等待协调 worker 时短暂休眠”的分支；确认实验 worker 数等于可用 CPU 数，不走该分支。两份构建分别保留在归档中，不能把不同二进制哈希说成相同；默认构建通过上述常规和超额检查。

选择的默认选项与其他实验开关见 `native/CMakeLists.txt`。SRE 用相同冻结 JAR / classes 和新 JNI 即可运行，不需要源码适配。

已有 CMake 构建目录会保留缓存值，显式启用默认选择用：

```sh
cmake -S native -B native/build-par-final -DCMAKE_BUILD_TYPE=Release \
  -DPNDD_OPTIMISTIC_CACHE=ON -DPNDD_ROOT_AFFINITY=ON \
  -DPNDD_BATCH_GRAIN=OFF -DPNDD_PENDING_PARTITION=OFF -DPNDD_LABEL_GRAIN=OFF
cmake --build native/build-par-final -j6
```

`final/` 归档默认 JNI、C driver、确认实验 JNI、源码、编译配置与哈希；SRE / NDD JAR 和编译类仍使用上一轮冻结版本。

复现示例（从 NDD 目录执行；使用新的输出目录）：

```sh
python3 native/scripts/benchmark_core_scaling.py \
  --java /path/to/jdk17/bin/java \
  --classes results/sre_priority_batch_20261012/final/sre-classes \
  --jar results/sre_priority_batch_20261012/final/ndd.jar \
  --library current=/absolute/path/libpnddjni.so \
  --sizes 8,12 --workers 1,6 --repeats 2 \
  --out results/core_scaling_NEW
```

`--labels true` 考察标签任务；同一组单 / 六 workers 使用同样设置。纯 C N-Queens 用固定原编码和 left-fold 另测，不与 SRE 的绝对时间混在一起。
