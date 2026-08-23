package org.ants.jndd.diagram;

interface LabelDecisionDiagramBackend {
    NDD.LabelMode mode();

    boolean hasExplicitUniverse();

    Object rawEngine();

    int createVariableLabel();

    int variableId(int label);

    int buildUniverse(int[] variableLabels, int offset, int length);

    int positiveLiteral(int universe, int variableLabel);

    int negativeLiteral(int universe, int variableLabel);

    /** Build one referenced label for a concrete value over a contiguous variable slice. */
    default int concreteValueLabel(int universe, int[] variableLabels,
            int offset, int length, int value) {
        int result = ref(universe);
        for (int bit = 0; bit < length; bit++) {
            int shift = length - 1 - bit;
            int literal = ((value >>> shift) & 1) == 0
                    ? negativeLiteral(universe, variableLabels[offset + bit])
                    : positiveLiteral(universe, variableLabels[offset + bit]);
            int next = ref(and(result, literal));
            deref(result);
            result = next;
        }
        return result;
    }

    int ref(int label);

    void deref(int label);

    int and(int left, int right);

    /**
     * Whether {@code label} contains the concrete field assignment represented by
     * {@code assignment}. Both handles belong to this backend.
     */
    boolean matches(int label, int assignment);

    int or(int left, int right);

    int diff(int universe, int left, int right);

    int not(int universe, int label);

    int orTo(int current, int add);

    int andTo(int current, int other);

    double satCount(int label, int fieldBits, int maxBits);

    /** Number of concrete assignments exposed by a small truth-table backend, or zero. */
    default int assignmentCapacity() {
        return 0;
    }

    /** Populate target entries selected by a label. Only valid when capacity is nonzero. */
    default void fillAssignmentTargets(int label, int target, int[] targets, int offset) {
        throw new UnsupportedOperationException("backend has no explicit assignments");
    }

    /** Canonical singleton label for one explicit assignment. */
    default int assignmentLabel(int assignment) {
        throw new UnsupportedOperationException("backend has no explicit assignments");
    }

    long nodeCount();

    long totalCreated();

    long gcCount();

    long gcFreedCount();

    long gcTimeMillis();

    long gcNotifyTimeMillis();

    long growCount();

    long growTimeMillis();

    void gc();

    void showStats();
}
