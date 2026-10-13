#!/usr/bin/env python3
"""Aggregate the recorded fresh-process matrix without removing failed runs."""
import argparse, csv, json, statistics
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('directory',type=Path);a=p.parse_args()
root=a.directory.resolve(); runs=[json.loads(line) for line in (root/'runs.jsonl').read_text().splitlines()]
meta=json.loads((root/'metadata.json').read_text()); repeats=meta['arguments']['repeats']
summary=[]; timings=[]; memories=[]; details=[]
def avg(group,key):
    v=[r[key] for r in group if r['status']=='ok' and key in r]
    return statistics.mean(v) if len(v)==repeats else None
def f(value,digits=3): return f'{value:.{digits}f}' if value is not None else '—'
for size in sorted({r['size'] for r in runs}):
 for mf in sorted({r['mf'] for r in runs}):
    case=[r for r in runs if r['size']==size and r['mf']==mf]
    if not case: continue
    groups={k:[r for r in case if r['engine']==k] for k in ['java','native1','native6']}
    out=dict(size=size,mf=mf)
    for engine,g in groups.items():
        for key in ['total','src_compute','src_rib_dump','spf','init','mine','wall_seconds','peak_rss_mib','timed_peak_rss_mib','native_external_runs','native_tasks','native_stolen_tasks','native_collections','native_peak_bytes']:
            out[engine+'_'+key]=avg(g,key)
        out[engine+'_status']='ok' if len(g)==repeats and all(r['status']=='ok' for r in g) else ','.join(r['status'] for r in g) or 'pending'
    t=[out[k+'_total'] for k in groups]
    out['native_scaling']=t[1]/t[2] if t[1] and t[2] else None
    out['java_vs_native6']=t[0]/t[2] if t[0] and t[2] else None
    out['bgp_routes']=case[0].get('bgp_routes')
    summary.append(out)
    timings.append(f'| {size:02d} | {mf} | {f(t[0])} | {f(t[1])} | {f(t[2])} | {f(out["native_scaling"],2)}× | {f(out["java_vs_native6"],2)}× |')
    memories.append(f'| {size:02d} | {mf} | '+ ' | '.join(f(out[k+'_peak_rss_mib'],1) for k in groups)+' | '+ ' | '.join(f(out[k+'_wall_seconds']) for k in groups)+' |')
    for engine,g in groups.items():
        details.append(f'| {size:02d} | {mf} | {engine} | '+ ' | '.join(f(out[engine+'_'+key]) for key in ['init','src_compute','src_rib_dump','spf','mine'])+' |')
with (root/'summary.csv').open('w',newline='') as stream:
    writer=csv.DictWriter(stream,fieldnames=list(summary[0]));writer.writeheader();writer.writerows(summary)
(root/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
complete=len(runs)==len(json.loads((root/'schedule.json').read_text()))
check=json.loads((root/'checks.json').read_text()) if (root/'checks.json').exists() else []
allok=complete and all(r['status']=='ok' for r in runs) and bool(check) and all(all(v for k,v in c.items() if k not in ['size','mf']) for c in check)
ratios=[r['java_vs_native6'] for r in summary if r['java_vs_native6'] is not None]
scaling=[r['native_scaling'] for r in summary if r['native_scaling'] is not None]
conclusion='完整矩阵尚未完成，暂不作最终性能结论。'
if allok and ratios:
    conclusion=('这条 SRE 接入路径在九个配置上均未取得相对 Java 的提速。'
                if max(ratios)<1 else '不同配置的收益不同，以下按实测结果逐项比较。')
largest=next((r for r in summary if r['size']==12 and r['mf']==3),None)
large_note=''
if largest and all(largest[k+'_total'] is not None for k in ['java','native1','native6']):
    memory_ratio=largest['native6_peak_rss_mib']/largest['java_peak_rss_mib']
    large_note=(f"fattree12/MF=3 的 native6 峰值 RSS 平均为 Java 的 {memory_ratio:.1%}，"
                f"但总耗时为 Java 的 {largest['native6_total']/largest['java_total']:.2f} 倍。"
                f"仅看控制面计算，Java 为 {largest['java_src_compute']:.3f} s，"
                f"native6 为 {largest['native6_src_compute']:.3f} s；"
                "差异并非仅由 RIB 文件输出引起。")
text=f'''# SRE 全 BCDD 并行 NDD 实测（2026-10-11）

本报告比较迁移后的当前 Java NDD、native 1 worker、native 6 workers。
当前完成 {len(runs)} 个独立进程；完整性及跨引擎结果检查：{'全部通过' if allok else '未完成或存在失败，检查 runs.jsonl / checks.json'}。
每个配置运行 {repeats} 次，下表为算术平均值；两次样本不足以给出稳定的统计置信区间。
{conclusion}

## 应用总耗时

单位秒，使用 SRE 原有 `total`；包含初始化、控制面传播、RIB 文件输出、数据面分析及清理阶段，
不包含 JVM 启动、追加的语义校验和 native 引擎关闭。
`native 自身加速 = T(native1)/T(native6)`，`相对 Java = T(Java)/T(native6)`；大于 1 才表示加速。

| fattree | MF | Java s | native1 s | native6 s | native 自身加速 | 相对 Java |
|---|---:|---:|---:|---:|---:|---:|
'''+ '\n'.join(timings)+f'''

本次相对 Java 的倍率范围为 {f(min(ratios) if ratios else None,2)}–{f(max(ratios) if ratios else None,2)}×，
native 自身的并行倍率范围为 {f(min(scaling) if scaling else None,2)}–{f(max(scaling) if scaling else None,2)}×。
{large_note}

## 内存与完整进程耗时

RSS 单位 MiB，取每次进程 VmHWM 的最大观测值，再对两次进程求平均；50 ms 采样。
完整进程耗时包含 JVM 启动、语义校验和 native 关闭，以及父进程最多约 50 ms 的轮询延迟；短样本仅供参考。`runs.csv` 另保留计时阶段前的 RSS 观测。

| fattree | MF | Java RSS | native1 RSS | native6 RSS | Java wall s | native1 wall s | native6 wall s |
|---|---:|---:|---:|---:|---:|---:|---:|
'''+ '\n'.join(memories)+'''

## 阶段耗时

`src_compute` 是控制面计算；`src_rib_dump` 是 RIB 序列化。
二者及少量包装开销构成原始 `src`。`spf` 为转发表、ACL、可达性分析。

| fattree | MF | 引擎 | init s | src_compute s | src_rib_dump s | spf s | mine s |
|---|---:|---|---:|---:|---:|---:|---:|
'''+ '\n'.join(details)+f'''

## 比较条件与迁移

- 输入：fattree04/08/12，MF=1/2/3，F 组全 BCDD，Header-Link，plan=1，保留原始 trim。
- 每配置两次新 JVM；固定种子交错运行，进程串行执行，绑定 CPU `{meta['arguments']['cpus']}`。
- JDK：{meta['java'].splitlines()[0]}；C Release：{meta['compiler'].splitlines()[0]}。
- JVM `-Xmx8g`；native 图预算 3072 MiB；所有进程 RSS 上限 12288 MiB，超时 900 s。
  native 图预算额外占用进程内存，不能把 JVM 堆上限误当成 native 总内存上限。
- Native 缓存 262144，阈值/块大小/深度 4/2/4；NDD 内部并行开启，标签内部并行关闭；
  adapter 每新增 16384 个句柄检查一次可达性，native GC interval=0，预算压力时仍可 GC。
- BCDD 标签统一 legacy LSB-first 创建编号顺序；NDD 默认的 MSB-first 没有用于这次比较。
- SRE 不再编译历史嵌入 NDD，使用当前 NDD Maven 库；适配旧整数 ID、借用引用、标签检查和 Header 转换。
  这次测量使用 scalar JNI，尚未把 SRE 控制面传播改成 native batch。

`fattree04/MF=2,3` 在原始 trim 下均为 **0 BGP 路由**，主要衡量初始化/过滤及连接路由开销，
不能作为完整 BGP 并行负载。当前输入的最终 reachability CSV 可能仅有表头，
因此额外检查所有 RIB/FIB 条件，不能仅以输出文件相同认定语义一致。

## 验证与解释

初次测量发现 Java BCDD 对 TRUE 终端未做补边规范化，等价函数可获得不同句柄，
继而导致 NDD 结构指纹不同。独立展开为 BDD 证实诊断条件等价；修复后回归测试及完整条件校验通过。
本报告重新运行两轮，初次测量保存在相邻 `sre_parallel_20261011` 目录，仅作诊断，不参与汇总。

检查内容包括 BGP 路由数、字段布局、link 顺序和极性、RIB/FIB 路由条件以及规范化可达性 CSV。
路由条件校验使用规范 DAG 的 64 位结构指纹，然后对排序后的路由描述符做 SHA-256；属于概率性回归校验。
小域枚举、宽字段、GC/引用生命周期及 Header 转换测试补充验证；不是大域形式化等价证明。

归档验证：native CTest 1/2/4/6 全过；NDD JUnit 33 项全过；SRE JUnit 35 项全过；
ASan/UBSan 1/6 workers 通过；SRE native6 的 `-Xcheck:jni` 冒烟测试通过，
条件摘要与正式样本一致（不计入性能均值）。日志见 [validation](validation/)。

性能解释需要区分 NDD 内部并行、标签并行和应用级独立任务。
SRE 保留原有控制面传播依赖顺序，大量细粒度 scalar JNI、整数句柄映射/可达性回收和 top-level Lace RUN
增加固定开销；大型 BCDD 标签工作在此次配置中仍走串行。
BCDD 复用 Sylvan 的节点表和 GC，但 apply 使用统一标签递归和共享的 262144 项缓存，
没有直接调用 Sylvan 优化后的 BDD apply 内核。fattree08 和 fattree12/MF=1 的 Java 标签缓存为 2097152 项，
fattree12/MF=2,3 则为 8388608 项；容量配置不同，按相同进程 RSS 上限比较，不能称为缓存等容量实验。
各项开销占比尚未单独 profile，不应把所有差异都归因于 JNI 或线程调度。
当前结果衡量这条迁移路径，不能直接套用 N-Queens 的粗粒度并行收益。
NDD 任务、窃取、入口、缓存和 GC 计数均在逐次日志中，可据此选择下一步优化。

## 复现与原始数据

脚本位于 SRE `experiments/parallel-ndd/benchmark.py`，使用新的结果目录：

```sh
cd /home/cheney/SRE
python3 experiments/parallel-ndd/benchmark.py \\
  --ndd /home/cheney/NDD --output /home/cheney/NDD/results/sre_parallel_NEW \\
  --cpus 0,2,4,6,8,10 --repeats 2
python3 experiments/parallel-ndd/report.py /home/cheney/NDD/results/sre_parallel_NEW
```

- [逐次结果 CSV](runs.csv)、[逐次 JSONL 和命令](runs.jsonl)、[汇总 CSV](summary.csv)、[语义检查](checks.json)。
- [运行环境、容量和输入哈希](metadata.json)、[执行顺序](schedule.json)、[原始日志](logs/)。
- [NDD 源码快照](ndd-sources.tar.gz)、[SRE 源码快照](sre-sources.tar.gz)，以及 `ndd.diff` / `sre.diff`。
  基准开始时包含未提交的适配修改，以快照和 binary/JAR SHA-256 精确标识代码。
  两份 README 及报告等文档可能在基准运行期间继续完善，执行源码保持冻结。
- NDD 基础提交 `{meta['ndd_commit']}`；SRE 基础提交 `{meta['sre_commit']}`。

历史 N-Queens 数据保留在原目录；本报告是独立 SRE 实验，输入、变量顺序和容量不同。
'''
(root/'README.md').write_text(text)
print(f'{len(runs)} runs; complete={complete}; checks={allok}')
