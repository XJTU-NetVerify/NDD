# 旧 MTPNDD 兼容模块与历史数据

推荐使用根README中的 `native/` 共享核心及 `ParallelMTNDD` / `MTNDDNode`。本目录保留 `~/mtpndd` 的原型源码、历史benchmark与旧JNI；它的borrowed API已deprecated，新路径的owned-root/GC保证不适用于旧静态接口。

旧模块仍可独立构建：

```sh
cmake -S mtndd -B mtndd/build -DCMAKE_BUILD_TYPE=Release
cmake --build mtndd/build -j6
ctest --test-dir mtndd/build --output-on-failure
mvn -f mtndd/jni/pom.xml -Dorg.ants.mtpndd.library.path="$PWD/mtndd/build/jni/libmtpnddjni.so" test package
```

[当前完成度](docs/STATUS.md) / [迁移](docs/MIGRATION.md) / [旧路径审查](docs/status-legacy-20261013.md) / [历史性能](results/upstream-20260422/README.md) / [上游README](UPSTREAM_README.md) / [来源](UPSTREAM.json)。
