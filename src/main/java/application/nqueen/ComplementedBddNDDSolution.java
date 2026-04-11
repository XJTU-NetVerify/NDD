package application.nqueen;

/**
 * Dedicated benchmark entrypoint for the complemented-edge BDD backed NDD.
 */
public final class ComplementedBddNDDSolution {
    private ComplementedBddNDDSolution() {}

    public static void main(String[] args) {
        String[] delegated = new String[args.length + 1];
        delegated[0] = "--bcdd";
        System.arraycopy(args, 0, delegated, 1, args.length);
        NDDSolution.main(delegated);
    }
}
