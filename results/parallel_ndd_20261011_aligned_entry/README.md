# Warm entry benchmark

Sequential shuffled paired rounds; fresh process per sample. Warm cache-hit AND; scalar then batch; includes release. GC and batch task splitting disabled. Scalar warmup 2000 operations; Java additionally warms 20 batches. Timings exclude init and warmup; this is not cold N-Queens.

| Engine | Workers | Scalar µs/op | Batch µs/op | Scalar/batch |
|---|---:|---:|---:|---:|
| c | 1 | 10.658 | 0.130 | 82.03 |
| c | 6 | 0.623 | 0.086 | 7.21 |
| jni | 1 | 11.240 | 0.301 | 37.32 |
| jni | 6 | 0.761 | 0.225 | 3.37 |
