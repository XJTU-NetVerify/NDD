# Warm entry benchmark

Sequential shuffled paired rounds; fresh process per sample. Warm cache-hit AND; scalar then batch; includes release. GC and batch task splitting disabled. Scalar warmup 2000 operations; Java additionally warms 20 batches. Timings exclude init and warmup; this is not cold N-Queens.

| Engine | Workers | Scalar µs/op | Batch µs/op | Scalar/batch |
|---|---:|---:|---:|---:|
| c | 1 | 10.592 | 0.127 | 83.39 |
| c | 6 | 0.564 | 0.084 | 6.74 |
| jni | 1 | 11.317 | 0.288 | 39.29 |
| jni | 6 | 0.902 | 0.242 | 3.72 |
