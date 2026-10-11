#!/usr/bin/env python3
"""Five cold processes/configuration, warm cached AND inside each process."""
import argparse
import csv
import hashlib
import json
import random
import shutil
import statistics
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--java', default=shutil.which('java'))
    parser.add_argument('--repeats', type=int, default=5)
    parser.add_argument('--cores', default='0,2,4,6,8,10')
    args = parser.parse_args()
    if not args.java or args.repeats < 1:
        parser.error('A JDK and positive repetition count are required')
    cores = args.cores.split(',')
    if len(cores) < 6:
        parser.error('Supply six CPU IDs')
    out = args.out
    out.mkdir(parents=True, exist_ok=False)
    classes = out / 'classes'; classes.mkdir()
    jar = ROOT / 'target/ndd-1.0.1-jar-with-dependencies.jar'
    binary = ROOT / 'native/build-release/pndd_entry'
    library = ROOT / 'native/build-release/libpnddjni.so'
    subprocess.run([str(Path(args.java).with_name('javac')), '-cp', str(jar),
                    '-d', str(classes), str(ROOT / 'native/bench/EntryBenchmark.java')], check=True)
    files = [binary, library, jar, ROOT / 'native/bench/entry.c',
             ROOT / 'native/bench/EntryBenchmark.java', Path(__file__).resolve()]
    metadata = dict(repeats=args.repeats, cores=cores, operations=10000, batch_size=100,
                    sha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files},
                    methodology='Sequential shuffled paired rounds; fresh process per sample. Warm cache-hit AND; scalar then batch; includes release. GC and batch task splitting disabled. Scalar warmup 2000 operations; Java additionally warms 20 batches. Timings exclude init and warmup; this is not cold N-Queens.')
    (out / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    rows = []
    for round_number in range(1, args.repeats + 1):
        configs = [(e, w) for e in ('c', 'jni') for w in (1, 6)]
        random.Random(20261011 + round_number).shuffle(configs)
        for engine, workers in configs:
            if engine == 'c':
                command = [str(binary), str(workers), '10000', '100']
            else:
                command = [args.java, '-Xmx512m', f'-XX:ActiveProcessorCount={workers}',
                           f'-Dpndd.library={library}', '-cp', str(classes) + ':' + str(jar),
                           'org.ants.jndd.parallel.EntryBenchmark', str(workers), '10000', '100']
            command = ['taskset', '-c', ','.join(cores[:workers])] + command
            result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, timeout=120, check=True)
            (out / f'r{round_number}_{engine}_{workers}.log').write_text(json.dumps(command) + '\n' + result.stdout)
            row = json.loads(next(line for line in reversed(result.stdout.splitlines()) if line.startswith('{')))
            row['round'] = round_number; rows.append(row)
            print(row, flush=True)
    with (out / 'samples.csv').open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)
    summary = []
    report = ['# Warm entry benchmark', '', metadata['methodology'], '',
              '| Engine | Workers | Scalar µs/op | Batch µs/op | Scalar/batch |', '|---|---:|---:|---:|---:|']
    for engine in ('c', 'jni'):
        for workers in (1, 6):
            group = [r for r in rows if r['engine'] == engine and r['workers'] == workers]
            scalar = statistics.median(r['scalar_s'] for r in group)
            batch = statistics.median(r['batch_s'] for r in group)
            summary.append(dict(engine=engine, workers=workers, scalar_s=scalar, batch_s=batch,
                                scalar_us_per_op=scalar*100, batch_us_per_op=batch*100))
            report.append(f'| {engine} | {workers} | {scalar*100:.3f} | {batch*100:.3f} | {scalar/batch:.2f} |')
    (out / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    (out / 'README.md').write_text('\n'.join(report) + '\n')

if __name__ == '__main__':
    main()
