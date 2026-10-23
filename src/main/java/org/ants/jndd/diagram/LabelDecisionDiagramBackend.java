package org.ants.jndd.diagram;

import java.util.Set;

interface LabelDecisionDiagramBackend {
    NDD.LabelMode mode();

    boolean hasExplicitUniverse();

    Object rawEngine();

    int createVariableLabel();

    int variableId(int label);

    int buildUniverse(int[] variableLabels, int offset, int length);

    int positiveLiteral(int universe, int variableLabel);

    int negativeLiteral(int universe, int variableLabel);

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

    long nodeCount();

    /**
     * Add the ids of the non-terminal nodes used by {@code label} to {@code visited}. Nodes already
     * in {@code visited} are skipped, so shared nodes are counted once across several labels.
     */
    void collectNodes(int label, Set<Integer> visited);

    long totalCreated();

    void gc();
}
