package org.ants.jndd.diagram;

interface LabelDecisionDiagramBackend {
    NDD.LabelMode mode();

    boolean isFiniteDomain();

    Object rawEngine();

    int createVariableLabel();

    int variableId(int label);

    int ref(int label);

    void deref(int label);

    int and(int left, int right);

    int or(int left, int right);

    int diff(int universe, int left, int right);

    int not(int universe, int label);

    int orTo(int current, int add);

    int andTo(int current, int other);

    double satCount(int label, int fieldBits, int maxBits);

    long nodeCount();

    long totalCreated();

    void gc();
}
