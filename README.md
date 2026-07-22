## About

**Network Decision Diagram (NDD)** is a new decision diagram, built on the classical [Binary Decision Diagram (BDD)](https://en.wikipedia.org/wiki/Binary_decision_diagram).
For BDD, each node looks at a single **bit** each time, and branches based on whether the bit is true or false;
in contrast, each NDD node looks at a **field** consisting of a fixed number of bits each time, and branches based on the value of the field.
As a result, there can be more than 2 branches for each NDD node. 

Different from other multi-valued decision diagram like MDD, NDD encodes the branching condition with **external data structures**.
Currently, our NDD libray supports several external data structures, including: BDD, complemented-edge BDD (BCDD), and zero-suppressed decision diagrams (ZDD).

An example of using BDD as the external data structure is shown in the figure below.
In this figure, we represent Hadamard matrix _H_<sub>4</sub>'s values on each coordinate (_x_<sub>0</sub>_x_<sub>1</sub>, _y_<sub>0</sub>_y_<sub>1</sub>) as a BDD (in (b)) and an NDD (in (c)). Each NDD node represents a 2-bit field (_f_<sub>1</sub> and _f_<sub>2</sub>), and the branching condition is encoded with 2 BDDs (in (d)).

![ndd-diagram](ndd_diagram.svg)

NDD can be seen as wrapping a lower-level decision diagram with an outer field-aware layer, and therefore the name of NDD can also be interpreted as "Nested Decision Diagram".

## Benchmark

The following table compares the performance of our NDD libray with the [JDD library](), and our original version of NDD submitted to NSDI '25.
We use different sizes of **NQueens** problem, and the time is in `seconds`. 

|  N | BDD (JDD) | NDD-Original  | NDD    |
| -- | --------- | ------------- | ------ |
| 10 |    0.5479 |        0.7315 | 0.2136 |
| 11 |    2.7947 |        2.7497 | 0.7619 |
| 12 |   19.0108 |       10.2289 | 3.4391 |
| 13 |  148.9701 |       66.7104 |23.6618 |

Detailed benchmark results, compared with more BDD libraries, are available on [nqueensBenchmarkDDs](https://github.com/XJTU-NetVerify/nqueensBenchmarkDDs)

The following table compares the performance of different external data structures for N-Queens `N=12`.

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
