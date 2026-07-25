## About

**Network Decision Diagram (NDD)** is a new decision diagram, built on the classical [Binary Decision Diagram (BDD)](https://en.wikipedia.org/wiki/Binary_decision_diagram).
For BDD, each node looks at a single **bit** each time, and branches based on whether the bit is true or false;
in contrast, each NDD node looks at a **field** consisting of a fixed number of bits each time, and branches based on the value of the field.
As a result, there can be more than 2 branches for each NDD node. 

Different from other multi-valued decision diagram like MDD, NDD encodes the branching condition with **external data structures**.
Currently, our NDD libray supports several external data structures, including: BDD, complemented-edge BDD (BCDD), and zero-suppressed decision diagrams (ZDD).

An example of using BDD as the external data structure is shown in the figure below.
In this figure, we represent Hadamard matrix _H_<sub>4</sub>'s values on each coordinate (_x_<sub>0</sub>_x_<sub>1</sub>, _y_<sub>0</sub>_y_<sub>1</sub>) as a BDD (in (b)) and an NDD (in (c)). Each NDD node represents a 2-bit field (_f_<sub>1</sub> and _f_<sub>2</sub>), and the branching condition is encoded with 2 BDDs (in (d)).

<img src="ndd_diagram.svg" width="100%">

NDD can be seen as wrapping a lower-level decision diagram with an outer field-aware layer, and therefore the name of NDD can also be interpreted as "Nested Decision Diagram".

## Manipulation APIs

Alongside `mk`, `and`, `or`, and `not`, NDD provides field-aware operations commonly expected from a decision-diagram package:

- `apply(...)` for named binary Boolean operations, plus `simplify(function, careSet)`
- `restrict(...)` to fix a field value and obtain its cofactor
- `satCount`, `anySat`, and callback-based `allSat`
- existential quantification over one or more fields: `exist(root, fields...)`
- field substitution/renaming: `substitute(root, sourceField, targetField)`

The [Manipulation API guide](https://github.com/XJTU-NetVerify/NDD/wiki/Manipulation-APIs) explains the field-level semantics, backend-specific value representation, and complete call examples.

## Benchmark

NDD is designed to make the field structure in symbolic workloads explicit. The following results show the effect on the two largest completed **N-Queens** instances in our cross-library run. `NDD` is the current optimized implementation and `NDD-Origin` is the original NDD version; lower is better.

| Implementation | Language | N=12 time (s) | N=13 time (s) |
| --- | --- | ---: | ---: |
| BuDDy | C | 41.098 | >500 (timeout) |
| CUDD | C | 28.663 | 194.928 |
| JDD | Java | 19.011 | 148.970 |
| JSylvan* | Java | 4.816 | 42.857 |
| DD-BDD | C# | 13.931 | 81.584 |
| DD-CBDD | C# | 9.730 | 55.487 |
| NDD-Origin | Java | 10.229 | 66.710 |
| **NDD** | **Java** | **3.439** | **23.662** |

On these instances, NDD is the fastest implementation in this run: **1.40x faster at N=12** and **1.81x faster at N=13** than the next fastest completed implementation. It is also **2.97x** and **2.82x** faster than NDD-Origin, and **5.53x** and **6.30x** faster than JDD, respectively. All implementations are single-threaded except JSylvan, which used 48 worker threads.

The [N-Queens results in the Wiki](https://github.com/XJTU-NetVerify/NDD/wiki/Results-NQueens) include the full table, memory footprint, node counts, NDD variants, and a comparison of BDD, ZDD, and complemented-edge BDD label backends. The dedicated [nqueensBenchmarkDDs](https://github.com/XJTU-NetVerify/nqueensBenchmarkDDs) repository contains the broader benchmarking harness and related implementations.

### Network Verification: WAN / SRE

N-Queens is easy to reproduce; WAN/SRE is the network-verification-oriented benchmark used to evaluate larger field-structured BGP/fattree workloads. A compact view of representative cases is below. `NDD-Origin` is the original baseline and `NDD` is the current optimized implementation.

| WAN / SRE dataset | Metric | BDD | NDD-Origin | NDD |
| --- | --- | ---: | ---: | ---: |
| `bgp_fattree08`, `MF=3` | total time | 103.809 s | 60.829 s | **25.602 s** |
| `bgp_fattree08`, `MF=3` | BDD nodes | 85.6 M | 38.2 M | **3.2 M** |
| `bgp_fattree12`, `MF=3` | total time | 2,350.774 s | 636.086 s | **230.906 s** |
| `bgp_fattree16`, `MF=2` | total time | >14,400 s (timeout) | 1,178.287 s | **472.056 s** |

See the [WAN/SRE results in the Wiki](https://github.com/XJTU-NetVerify/NDD/wiki/Results-SRE) for the complete experiment matrix, including peak memory, route counts, timeouts, and methodology notes. These research drivers require external datasets and are not part of the default Maven build.

### NDD Label Backends

The following table compares external data structures for N-Queens `N=12`.

| target | time (s) | memory (MB) | 
| --- | ---: | ---: |
| ndd-bdd | 3.0199 | 567.2 | 
| ndd-zdd | 2.8683 | 559.4 | 
| ndd-bcdd | 3.0480 | 742.1 | 

Full backend comparison results are in [`results/nqueens_backend_results.md`](results/nqueens_backend_results.md) and the wiki.

## The Origin of NDD

NDD was originally proposed for network verification, where each NDD node represents a packet header field (destination IP address)
We observed NDD was more efficient than BDD in terms of memory and computation.
The reason is due to the **locality** of field-based matching semantics, NDD can significantly reduce the number of label decision-diagram nodes for each field.

## Ongoing Work

The current NDD libary is by far not the end, and we are working on extending it to support: (1) multiple terminals, (2) parallel computation, (3) using NDD for more applications like modeling checking.

## Branches

* Main: Featuring an efficient design of node table.
* Reuse: Featuring the reuse of label decision-diagram variables among all fields.
* Original: The original prototype for NSDI '25 paper.

## Resources

- [wiki](https://github.com/XJTU-NetVerify/NDD/wiki)
- [NSDI Paper](https://www.usenix.org/system/files/nsdi25-li-zechun.pdf)
- [NSDI talk slides](https://xjtu-netverify.github.io/papers/NDD/NDD-A-Decision-Diagram-for-Network-Verification.pdf)
- [NSDI talk video](https://www.youtube.com/watch?v=9Ni6Z7qKGV4)

## Bibtex

```bibtex
@inproceedings{NDD,
  title={NDD: A Decision Diagram for Network Verification},
  author={Li, Zechun and Zhang, Peng and Zhang, Yichi and Yang, Hongkun},
  booktitle={22nd USENIX Symposium on Networked Systems Design and Implementation (NSDI 25)},
  pages={237--258},
  year={2025}
}
```

### Contact

- Peng Zhang (p-zhang@xjtu.edu.cn)
- Yichi Zhang (augists@outlook.com)
- Zechun Li (1467874668@qq.com)

## License

Apache-2.0. See [`LICENSE`](LICENSE).
