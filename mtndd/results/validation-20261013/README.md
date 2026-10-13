# 2026-10-13 验证

- Release：8项CTest全部通过，所有测试断言启用。
- Debug ASan/UBSan：同8项通过，detect_leaks=1、halt_on_error=1。
- Java/JNI：10项，零失败、零错误、零跳过。
- 数值oracle：两字段各16值，fraction/double的PLUS/MINUS/TIMES/DIVIDE、两个字段求和消去，workers=1/6逐赋值核对。
- 单槽缓存六写者碰撞、运算标签隔离、版本耗尽禁用；Java引擎关闭/重建、batch旧节点输入拒绝；32-bit Double基数及跳过字段。
- 原始四份TSV与上游逐字节SHA一致；没有新增性能测量。

配置和检查见 [checks.json](checks.json)，输出见 [Release](release-ctest.log)、[ASan/UBSan](asan-ubsan-ctest.log)。Java q模式成功时无控制台输出，计数从Surefire XML汇总。

这些是有限的正确性检查，不是高频GC与在途任务并发回收的完整证明。
