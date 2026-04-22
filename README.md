# A library for Network Decision Diagram (NDD)

This is an implementation of **N**etwork **D**ecision **D**iagram published on NDSI 2025.

> Zechun Li, Peng Zhang, Yichi Zhang, and Hongkun Yang. "NDD: A Decision Diagram for Network Verification", NSDI 2025

- Paper PDF: <https://www.usenix.org/system/files/nsdi25-li-zechun.pdf>
- NSDI 2025 page: <https://dl.acm.org/doi/10.5555/3767955.3767969>
- Paper slides: <https://xjtu-netverify.github.io/papers/NDD/NDD-A-Decision-Diagram-for-Network-Verification.pdf>
- Video: <https://www.youtube.com/watch?v=9Ni6Z7qKGV4>

## Introduction

**Network Decision Diagram (NDD)** is a new decision diagram based on the classical Binary Decision Diagram (BDD).
In BDD, each node looks at a single **bit**, and branches based on whether the bit is true or false;
while in NDD, each node looks at a **field** which either bears some semantics meaning, say an IP address, or simply a fixed number of bits.
Since the node may have more than 2 branches, we represent the branching condition with external data structures.
Current, NDD uses BDD to represent the branching condition: if the field has $n$ bits, then the condition for each branch is a BDD with $n$ variables.
In this sense, NDD can be seen as wrapping the original BDD with another layer of decision diagram, and therefore can also be interpreted as "Nested Decision Diagram".

## Branches

* Array(main): Reuse branch -> rewrite node and node table in array with global edge stack.
* Reuse: Original branch -> reuse backend BDD node tables in different fields.
* Original: Separate fields and assemble as NDD.

## Benchmark

Benchmark (time `second`) on **NQueens**

|  N | BDD (JDD) | NDD-Original | NDD-Array |
| -- | --------- | ------------ | --------- |
| 10 |     0.615 |        0.344 |     0.214 |
| 11 |     2.567 |        2.257 |     0.762 |
| 12 |    19.109 |       12.417 |     4.101 |

BDDs and NDDs benchmark is available on [nqueensBenchmarkDD](https://github.com/XJTU-NetVerify/nqueensBenchmarkDD)

## The Origin of NDD

NDD was originally proposed for network verification, where each NDD node represents a packet header field (destination IP address)
We observed NDD was more efficient than BDD in terms of memory and computation.
The reason is due to the **locality** of field-based matching semantics, NDD can significantly reduce the number of BDD nodes for each field.
The figure below shows an example, where the three BDDs in (a) can be represented by three equivalent NDDs in (c), 
where each edge of which is labelled by per-field BDDs in (b).

![fig4 drawio](NDD.svg)

## Getting Started

Access details on [wiki](https://github.com/XJTU-NetVerify/NDD/wiki).

## Bibtex

```bibtex
@inproceedings{NDD,
  title={$\{$NDD$\}$: A Decision Diagram for Network Verification},
  author={Li, Zechun and Zhang, Peng and Zhang, Yichi and Yang, Hongkun},
  booktitle={22nd USENIX Symposium on Networked Systems Design and Implementation (NSDI 25)},
  pages={237--258},
  year={2025}
}
```

### Contact

- Zechun Li (1467874668@qq.com)
- Peng Zhang (p-zhang@xjtu.edu.cn)
- Yichi Zhang (augists@outlook.com)
- Hongkun Yang (hkyang@google.com)

## License

Apache-2.0. See [`LICENSE`](LICENSE).
