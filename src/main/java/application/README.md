To show how to use NDD, we put an example of solving the N-Queens problem with NDD under 'application/nqueens/', which has been widely used as benchmark to test the performance of different BDD libraries.

* `BDDSolution.java` - jdd
* `NDDSolution.java` - shared N-Queens constraints with `--implementation jndd|javandd` (default: jndd). jndd supports BDD, complemented BDD and ZDD labels; javandd supports BDD labels.
* `nqueens/NQuenensBenchmark.java` - run this file to compare BDD and NDD performance in the N-Queens problem
