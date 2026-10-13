# 共享核心验证（2026-10-13）

新路径 `native/`＋`ParallelMTNDD`。本目录仅正确性/资源测试，没有代数应用性能评测；旧性能原始文件保持原样。

- Release完整CTest：7项全部通过；原有Boolean回归及两个数值worker配置。
- Debug ASan/UBSan：7项全部通过；完整共享manager回归；后续新增数值压力用相同sanitizers验证。
- 数值oracle：四后端、mixed字段、int64 rational/finite double、32赋值图，全部Boolean apply、四则、EXIST、加权消去、融合及dense导入。
- 运行中标签GC：BCDD/ZDD各32,768个prefix×suffix数值乘法组合、两轮、1/6 workers；断言首轮运算期间label collections增长，所有输出仍为1/3、支持数1。
- 资源边界：频繁显式GC与自动GC、64-entry碰撞缓存、stale cookie、失败batch没有部分owned输出、预算耗尽collect后恢复、精确2^54−1基数。
- Java：全量41项通过，零失败/错误/跳过（新MT5项、Boolean并行12项）；owned roots/retain/close、旧/跨引擎对象拒绝、Boolean/MT共享准入、6外部线程并发调用和collect、一次RUN batch、dense导入、宽域double和异常恢复。

精确命令、测试计数、日志及两CPU上的超额worker验证见 `checks.json` 和相邻日志。功能测试耗时不是benchmark结果。有限压力测试不声称形式化证明所有调度安全。
