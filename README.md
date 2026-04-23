## About

**Network Decision Diagram (NDD)** is a new decision diagram data structure based on the classical Binary Decision Diagram (BDD).
In BDD, each node looks at a single **bit**, and branches based on whether the bit is true or false;
while in NDD, each node looks at a **field** consisting of a fixed number of bits, and branches based on the value of the corresponding field.
Since there can be more than 2 branches, NDD encodes the branching condition with external data structures.
Currently, NDD uses BDD to represent the branching condition: if the field has $n$ bits, then the condition is a BDD with $n$ variables.
In this sense, NDD can be seen as wrapping the original BDD with an outter layer of decision diagram, and therefore the name of NDD can also be interpreted as "Nested Decision Diagram".

## Branches

* Main: Featuring an efficient design of node table.
* Reuse: Featuring the reuse of BDD node tables among all fields.
* Original: The original prototype for NSDI '25 paper.

## Benchmark

Run time (`second`) on different sizes of **NQueens** problem.

|  N | BDD (JDD) | NDD-Original | NDD       |
| -- | --------- | ------------ | --------- |
| 10 |     0.5479|        0.7315|     0.2136|
| 11 |     2.7947|        2.7497|     0.7619|
| 12 |    22.8852|       14.6047|     4.1006|

Detailed benchmark results are available on [nqueensBenchmarkDD](https://github.com/XJTU-NetVerify/nqueensBenchmarkDD)

## The Origin of NDD

NDD was originally proposed for network verification, where each NDD node represents a packet header field (destination IP address)
We observed NDD was more efficient than BDD in terms of memory and computation.
The reason is due to the **locality** of field-based matching semantics, NDD can significantly reduce the number of BDD nodes for each field.
The figure below shows an example, where the three BDDs in (a) can be represented by three equivalent NDDs in (c), 
where each edge of which is labelled by per-field BDDs in (b).

![fig4 drawio](NDD.svg)

## Resources

- [wiki](https://github.com/XJTU-NetVerify/NDD/wiki).
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

- Zechun Li (1467874668@qq.com)
- Peng Zhang (p-zhang@xjtu.edu.cn)
- Yichi Zhang (augists@outlook.com)
- Hongkun Yang (hkyang@google.com)

## License

Apache-2.0. See [`LICENSE`](LICENSE).
