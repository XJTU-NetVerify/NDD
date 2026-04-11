# NDD-SoA

`NDD-SoA` is a performance-oriented branch of **Network Decision Diagram (NDD)**. It keeps the original NDD idea and benchmark targets, but replaces the object-heavy internal representation with a structure-of-arrays layout and a shared edge-collection stack so the hot path allocates less and reuses more.

This tree is prepared as the optimized branch snapshot for upstreaming back into `NDD`. The original paper is: [NDD: A Decision Diagram for Network Verification](https://xjtu-netverify.github.io/papers/NDD/NDD-final-version.pdf), NSDI 2025.

## What Changed

- Replaced the original nested `HashMap`-based node representation with array-backed storage in [`NodeTable.java`](src/main/java/org/ants/jndd/nodetable/NodeTable.java).
- Removed per-node `NDD` objects from the JNDD hot path; JNDD operations now work on integer node IDs backed by SoA storage.
- Replaced temporary edge maps with one global stack-based edge collector in [`NDD.java`](src/main/java/org/ants/jndd/diagram/NDD.java).
- Right-aligned fields so compatible domains share the same underlying BDD variables, reducing BDD node counts.
- Kept `JavaNDD` (`NDDFactory`) usage available for codebases that prefer a `BDDFactory`-style API.

## Variant Map

The benchmark results in this repository use the following names:

| Variant | Meaning |
| --- | --- |
| `ndd` | Original object-based NDD baseline |
| `ndd-reuse` | Baseline NDD plus shared-BDD-variable reuse |
| `ndd-soa` | The version in this repository: reuse + global edge stack + SoA node table |

## Repository Layout

- [`src/`](src/) contains the Java source code, including `jndd`, `javandd`, and application examples.
- [`doc/javadoc/`](doc/javadoc/) contains the regenerated API documentation for the SoA branch.
- [`lib/`](lib/) contains bundled third-party artifacts such as `jdd-111.jar`.
- [`results/`](results/) contains the current benchmark data and plots for this branch.
- [`wiki/Optimization-Summary.md`](wiki/Optimization-Summary.md) summarizes the optimization work in reviewer-facing form.
- [`wiki/`](wiki/) contains GitHub Wiki-ready pages for installation, usage, parameters, and detailed benchmark results.

## Quick Start

Build the project with Maven:

```bash
mvn -DskipTests package
```

The default Maven build targets the core `JNDD` / `JavaNDD` paths and the maintained examples. Experimental paths that still depend on the pre-SoA object-based API are excluded from the default compile for now:

- `org.ants.jndd.diagram.AtomizedNDD`
- `org.ants.jndd.nodetable.AtomizedNodeTable`
- `application.nqueen.FiniteDomainZddNDDSolution`
- `application.wan.bdd.*`
- `application.wan.ndd.*`

For the low-level `JNDD` API, the optimized implementation now uses integer node IDs:

```java
NDD.initNDD(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize);

for (int i = 0; i < fieldCount; i++) {
    NDD.declareField(fieldBitWidths[i]);
}
NDD.generateFields();

int acc = NDD.getFalse();
for (int bit = 0; bit < fieldBitWidths[0]; bit++) {
    acc = NDD.orTo(acc, NDD.getVar(0, bit));
}

double sat = NDD.satCount(acc);
```

For the `JavaNDD` factory-style API, see [`wiki/Usage.md`](wiki/Usage.md) and [`src/main/java/org/ants/javandd/README.md`](src/main/java/org/ants/javandd/README.md).

## Performance Snapshot

### NQueens

Compared with the original `NDD` baseline, `NDD-SoA` keeps the same solution counts while reducing runtime and memory use on the tested Java NQueens workload:

| Size | NDD time (s) | NDD-SoA time (s) | Speedup | NDD max RSS (KB) | NDD-SoA max RSS (KB) | RSS reduction |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 10 | 0.744 | 0.221 | 3.37x | 314148 | 134680 | 57.1% |
| 11 | 2.835 | 0.792 | 3.58x | 603572 | 218416 | 63.8% |
| 12 | 14.821 | 4.353 | 3.40x | 2233308 | 579796 | 74.0% |

Detailed tables and plots: [`wiki/Results-NQueens.md`](wiki/Results-NQueens.md)

### SRE

On the SRE benchmark set, the SoA implementation consistently improves over the original `ndd` variant on medium and large cases:

| Dataset | Metric | NDD | NDD-SoA | Improvement |
| --- | --- | ---: | ---: | ---: |
| `bgp_fattree08`, `MF=3` | total time (s) | 60.829 | 25.602 | 2.38x faster |
| `bgp_fattree08`, `MF=3` | peak RSS (MB) | 4220.4 | 2046.0 | 51.5% lower |
| `bgp_fattree08`, `MF=3` | BDD nodes | 38199434 | 3208829 | 91.6% fewer |
| `bgp_fattree12`, `MF=3` | total time (s) | 636.086 | 230.906 | 2.75x faster |
| `bgp_fattree12`, `MF=3` | peak RSS (MB) | 26274.3 | 18113.7 | 31.1% lower |
| `bgp_fattree16`, `MF=2` | total time (s) | 1178.287 | 472.056 | 2.50x faster |

Detailed tables: [`wiki/Results-SRE.md`](wiki/Results-SRE.md)

## Documentation

- Overview: [`wiki/Home.md`](wiki/Home.md)
- Optimization summary: [`wiki/Optimization-Summary.md`](wiki/Optimization-Summary.md)
- Installation and build: [`wiki/Installation.md`](wiki/Installation.md)
- API usage: [`wiki/Usage.md`](wiki/Usage.md)
- Parameter guide: [`wiki/Parameters.md`](wiki/Parameters.md)
- Benchmark methodology: [`wiki/Benchmarks.md`](wiki/Benchmarks.md)
- Design notes for this branch: [`wiki/Design-Notes.md`](wiki/Design-Notes.md)

## Paper and Citation

- Paper PDF: <https://xjtu-netverify.github.io/papers/NDD/NDD-final-version.pdf>
- NSDI 2025 page: <https://www.usenix.org/conference/nsdi25/presentation>

```bibtex
@inproceedings{NDD,
  author = {Zechun Li and Peng Zhang and Yichi Zhang and Hongkun Yang},
  title = {{NDD}: A Decision Diagram for Network Verification},
  booktitle = {22th USENIX Symposium on Networked Systems Design and Implementation (NSDI 25)},
  year = {2025},
  url = {https://www.usenix.org/conference/nsdi25/presentation},
  publisher = {USENIX Association},
  month = apr
}
```

## License

Apache-2.0. See [`LICENSE`](LICENSE).
