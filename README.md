# NDD: Network Decision Diagram

[![CI](https://github.com/XJTU-NetVerify/NDD/actions/workflows/ci.yml/badge.svg)](https://github.com/XJTU-NetVerify/NDD/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

## About

**Network Decision Diagram (NDD)** is a new decision diagram, built on the classical [Binary Decision Diagram (BDD)](https://en.wikipedia.org/wiki/Binary_decision_diagram).
For BDD, each node looks at a single **bit** each time, and branches based on whether the bit is true or false;
in contrast, each NDD node looks at a **field** consisting of a fixed number of bits each time, and branches based on what set of values the field takes.
But different from the classical multi-valued decision diagram (MDD), where a branch takes one **concrete** value from a domain, each branch in NDD takes a **symbolic** value, i.e., a subset of values from a domain.

The branching conditions are compactly encoded with interchangeable label representations,
including BDD, [complemented-edge BDD (BCDD)](https://dl.acm.org/doi/10.1145/123186.123222),
[zero-suppressed decision diagrams (ZDD)](https://en.wikipedia.org/wiki/Zero-suppressed_decision_diagram),
and explicit BITSET truth tables for small domains.
In this sense, NDD can be seen as wrapping a lower-level decision diagram with an outer field-aware layer, and therefore the name of NDD can also be interpreted as "Nested Decision Diagram".

**An example of NDD** In the figure below, we represent Hadamard matrix _H_<sub>4</sub>'s values on each coordinate (_x_<sub>0</sub>_x_<sub>1</sub>, _y_<sub>0</sub>_y_<sub>1</sub>) as a BDD (in (b)) and an NDD (in (c)). Each NDD node represents a 2-bit field (_f_<sub>1</sub> and _f_<sub>2</sub>), and the branching condition is encoded with 2 BDDs (in (d)).

<img src="ndd_diagram.svg" width="100%">

NDD was originally proposed for network verification, where each NDD node represents a packet header field (e.g., destination IP address).
We observed that NDD was more efficient than BDD in terms of memory and computation.
The reason is that, due to the **locality** of field-based matching semantics, NDD can significantly reduce the number of nodes.

## Features

- **Far fewer nodes than BDD**: by branching on whole fields and sharing label decision diagrams across fields, NDD can use far fewer nodes than BDD for field-structured problems, such as network verification, due to field locality. For example, on N-Queens with N=13, NDD creates about 14.6× fewer nodes than BDD libraries (see [Benchmark](#benchmark)). This reduces memory use (see the [NSDI paper](https://www.usenix.org/system/files/nsdi25-li-zechun.pdf) and the [network verification results](content/Results-SRE.md)).
- **Faster than BDD**: with fewer nodes to build and visit, operations do less work. On N-Queens with N=13, single-threaded NDD takes 27.2 s, about 8× faster than CUDD (224.4 s) and JDD (219.0 s), 3.1× faster than the fastest single-threaded BDD library in the [Benchmark](#benchmark) (DecisionDiagrams with CBDDNode, 83.6 s), and on par with JSylvan running on 48 threads (28.7 s).
- **Multiple label backends**: edge labels can be encoded as BDD, complemented-edge BDD (BCDD), ZDD, or BITSET (for fields of at most 7 bits). Backends can be mixed, with each field using its own backend (see [Design Notes](content/Design-Notes.md)).
- **Multiple terminals**: Multi-Terminal NDD (MTNDD) allows terminals to hold numeric values (integer, rational, or floating point) instead of only `true`/`false`, with arithmetic operations (`plus`, `minus`, `times`, `divide`) and sum abstraction. Useful for weights, costs, link loads, probabilities, and integer matrices. Available on the [`mtndd`](https://github.com/XJTU-NetVerify/NDD/tree/mtndd) branch.
- **Parallelism**: a C11 core with a JNI-based Java API runs NDD operations on multiple worker threads, reaching about 3.5× speedup with 6 workers on N-Queens. Available on the [`parallel`](https://github.com/XJTU-NetVerify/NDD/tree/parallel) branch (Boolean NDD) and the [`parallel-MTNDD`](https://github.com/XJTU-NetVerify/NDD/tree/parallel-MTNDD) branch (multi-terminal NDD); the native build is currently documented for Linux.

## Quick Start

**Prerequisites**: JDK 11 or later, Maven 3, and Git.

1. Clone and build the library. This produces `target/ndd-1.0.1-jar-with-dependencies.jar`, which bundles NDD with all its dependencies.

   ```bash
   git clone https://github.com/XJTU-NetVerify/NDD.git
   cd NDD
   mvn -DskipTests package
   ```

   To add NDD as a dependency of your own Maven project, see
   [Installation](content/Installation.md#using-ndd-in-another-project).

2. Save the following as `QuickStart.java`. It builds the Hadamard matrix example from the figure above
   as both a BDD and an NDD, and compares their sizes:

   ```java
   import jdd.bdd.BDD;
   import jdd.util.Dot;
   import org.ants.jndd.diagram.NDD;

   public class QuickStart {
       public static void main(String[] args) {
           // Hadamard matrix H_{2^n}: entry (x, y) is -1 iff x0·y0 XOR x1·y1 XOR ... = 1.
           // Arguments: [n] [--dot]. n defaults to 2, which gives H4 from the figure;
           // --dot also writes both diagrams as Graphviz .dot files.
           int n = 2;
           boolean dot = false;
           for (String arg : args) {
               if (arg.equals("--dot")) dot = true;
               else n = Integer.parseInt(arg);
           }

           // BDD: one variable per bit, ordered x0, y0, x1, y1, ... as in (b).
           BDD bdd = new BDD(10_000, 1_000);
           int h = bdd.getZero();
           for (int i = 0; i < n; i++) {
               int xi = bdd.createVar();
               int yi = bdd.createVar();
               h = bdd.xor(h, bdd.and(xi, yi));
           }

           // NDD: one 2-bit field (xi, yi) per i, as in (c).
           NDD.initNDD(10_000, 1_000, 10_000, 1_000);
           for (int i = 0; i < n; i++) {
               NDD.declareField(2);
           }
           NDD.generateFields();
           int ndd = NDD.getFalse();
           for (int i = 0; i < n; i++) {
               int xiAndYi = NDD.and(NDD.getVar(i, 0), NDD.getVar(i, 1));
               ndd = NDD.apply(NDD.BinaryOperation.XOR, ndd, xiAndYi);
           }

           // An NDD has two kinds of nodes: NDD nodes, and the BDD nodes inside its edge labels.
           System.out.println("BDD: " + bdd.nodeCount(h) + " BDD nodes");             // 4n - 2 (6 for n = 2)
           System.out.println("NDD: " + NDD.nodeCount(ndd) + " NDD nodes + "          // 2n - 1 (3 for n = 2)
                   + NDD.labelNodeCount(ndd) + " BDD nodes in edge labels");          // 4 for any n

           if (dot) {
               // Keep the .dot files: by default JDD runs Graphviz on them and then deletes them.
               Dot.setExecuteDot(false);
               Dot.setRemoveDotFile(false);
               bdd.printDot("hadamard-bdd.dot", h);
               NDD.printDot(ndd, "hadamard-ndd.dot");
               System.out.println("Wrote hadamard-bdd.dot and hadamard-ndd.dot");
           }
       }
   }
   ```

   The NDD needs half as many NDD nodes as the BDD needs BDD nodes, and the gap grows with `n`. Its edge labels add only
   two small BDDs, `xi ∧ yi` and `¬(xi ∧ yi)` as in (d), because all fields share the same label
   variables and therefore the same label BDDs.

   NDD calls must follow this order: `initNDD(...)`, then `declareField(...)` for every field, then `generateFields()`, and only then logical operations.
   For brevity, this example skips `ref()`/`deref()`; larger programs should protect the nodes they keep (see [Usage](content/Usage.md#jndd-reference-handling-notes)).

3. Compile and run it against the jar:

   ```bash
   javac -cp target/ndd-1.0.1-jar-with-dependencies.jar QuickStart.java
   java -cp target/ndd-1.0.1-jar-with-dependencies.jar:. QuickStart
   ```

   On Windows, the classpath separator is `;`. In cmd or PowerShell, use `-cp target/ndd-1.0.1-jar-with-dependencies.jar;.`;
   in Git Bash, quote it: `-cp "target/ndd-1.0.1-jar-with-dependencies.jar;."`.

   The same example is included in the repository as
   [`QuickStart.java`](src/main/java/application/examples/QuickStart.java) and is built into the jar,
   so you can also run it without saving the file:

   ```bash
   java -cp target/ndd-1.0.1-jar-with-dependencies.jar application.examples.QuickStart
   ```

   Both ways accept the matrix size `n` as an optional argument (default 2). For example, add `10` to the
   end of either `java` command to build H<sub>1024</sub>, which prints 38 BDD nodes for the BDD, and 19 NDD nodes plus 4 label BDD nodes for the NDD.

   Add `--dot` to also write both diagrams as Graphviz files, `hadamard-bdd.dot` and `hadamard-ndd.dot`,
   in the current directory. Render them with [Graphviz](https://graphviz.org/), for example
   `dot -Tpng hadamard-ndd.dot -o hadamard-ndd.png`.

## Benchmark

The following table shows the benchmark for **N-Queens** with N=12 and N=13 (best values in bold).

| Implementation | Language | N=12 time (s) | N=13 time (s) | N=13 nodes created |
| --- | --- | ---: | ---: | ---: |
| BuDDy | C | 53.601 | >500 | – |
| CUDD | C | 31.968 | 224.422 | 136,780,708 |
| JDD | Java | 26.878 | 219.003 | 136,725,331 |
| JSylvan* | Java | **3.750** | 28.725 | 136,728,750 |
| JavaBDD-C | Java/C | 24.439 | 230.309 | 136,589,564 |
| JavaBDD | Java | 27.867 | 293.619 | 136,711,984 |
| [DecisionDiagrams](https://github.com/microsoft/DecisionDiagrams) (BDDNode) | C# | 15.511 | 105.936 | 101,810,366 |
| [DecisionDiagrams](https://github.com/microsoft/DecisionDiagrams) (CBDDNode) | C# | 13.055 | 83.637 | 24,376,279 |
| NDD | Java | 3.823 | **27.172** | **9,378,160** |

\* JSylvan is a parallel library and was run with 48 worker threads; all other implementations, including NDD, are single-threaded.

NDD creates about 14.6× fewer nodes than the BDD libraries at N=13 (NDD nodes plus label BDD nodes).
Raw data and plots are in [`results/`](results/).

To rerun the NDD side of this benchmark, build the jar as in the [Quick Start](#quick-start) and run
[`NDDSolution.java`](src/main/java/application/nqueen/NDDSolution.java) for one or more board sizes:

```bash
java -cp target/ndd-1.0.1-jar-with-dependencies.jar application.nqueen.NDDSolution 8 9 10
```

It prints one `NQUEENS_METRICS` line per board size, with the number of solutions (e.g., 92 for N=8), node counts, and the running time.
Add `--bcdd` or `--zdd` before the sizes to switch the label backend.

For more details, please refer to [N-Queens Benchmark](content/Results-NQueens.md).

## Branches

- [`main`](https://github.com/XJTU-NetVerify/NDD/tree/main): The current library, with an efficient node table and multiple label backends.
- [`mtndd`](https://github.com/XJTU-NetVerify/NDD/tree/mtndd): Multi-Terminal NDD (MTNDD), whose terminals hold numeric values, with arithmetic operations.
- [`parallel`](https://github.com/XJTU-NetVerify/NDD/tree/parallel): Parallel Boolean NDD with a C11 core and a JNI-based Java API.
- [`parallel-MTNDD`](https://github.com/XJTU-NetVerify/NDD/tree/parallel-MTNDD): Parallel MTNDD, combining multiple terminals with the parallel C11 core.
- [`reuse`](https://github.com/XJTU-NetVerify/NDD/tree/reuse): Reuse of label decision-diagram variables among all fields.
- [`ndd-original`](https://github.com/XJTU-NetVerify/NDD/tree/ndd-original): The original prototype for the NSDI '25 paper.

## Ongoing Work

The current NDD library is by far not the end. Multiple terminals and parallel computation are now available on separate branches (see [Branches](#branches)), and we are working on (1) moving MTNDD into a separate repository as a standalone library, and (2) using NDD for more applications like model checking.

## Resources

- [API guide](content/Manipulation-APIs.md)
- [NSDI Paper](https://www.usenix.org/system/files/nsdi25-li-zechun.pdf)
- [NSDI talk slides](https://xjtu-netverify.github.io/papers/NDD/NDD-A-Decision-Diagram-for-Network-Verification.pdf)
- [NSDI talk video](https://www.youtube.com/watch?v=9Ni6Z7qKGV4)

## Citation

```bibtex
@inproceedings{NDD,
  title={NDD: A Decision Diagram for Network Verification},
  author={Li, Zechun and Zhang, Peng and Zhang, Yichi and Yang, Hongkun},
  booktitle={22nd USENIX Symposium on Networked Systems Design and Implementation (NSDI 25)},
  pages={237--258},
  year={2025}
}
```

## Contact

- Peng Zhang (p-zhang@xjtu.edu.cn)
- Yichi Zhang (augists@outlook.com)
- Zechun Li (1467874668@qq.com)

## License

Apache-2.0. See [`LICENSE`](LICENSE).
