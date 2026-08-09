package org.ants.jndd.diagram;


public class NDDManager{
    public static int ceil;
    /**
     * Creates an NDD manager with one of the available backends.
     * Use {@link #NDDManager(NDDBackend)} instead when aiming for more precise results
     * as there are no guarantees which backend is used with the getter.
     */
    public NDDManager(int nddTableSize, int bddTableSize, int bddCacheSize) {
        NDD.initNDD(nddTableSize, bddTableSize, bddCacheSize);
        installExternalCacheCleaner();
    }

    private static void installExternalCacheCleaner() {
        NDD.setExternalCacheCleaner(() -> {});
    }

    public NDDManager getInstance() {
        return this;
    }

    public NDD mk(int field, NDD[] targets, int[] labels) {
        return NDD.mk(field, targets, labels);
    }


    /**
     * Reads the epsilon parameter of the DD manager used for the precision of floating point values.
     * The default value is 1.0E-12.
     * <p>
     * Only available in CUDD.
     *
     * @return epsilon parameter
     */
    public double readEpsilon() {
        return NDD.readEpsilon();
    }

    /**
     * Sets the epsilon parameter of the DD manager used for the precision of floating point values.
     * The default value is 1.0E-12.
     * <p>
     * Only available in CUDD.
     *
     * @param epsilon New epsilon parameter
     */
    public void setEpsilon(double epsilon) {
        NDD.setEpsilon(epsilon);
    }



    /* Construct primitive NDDs */

    /**
     * Returns an NDD with constant 1.
     */
    public NDD readOne() {
        return NDD.getTrue();
    }

    /**
     * Returns an NDD with constant 0.
     */
    public NDD readZero() {
        return NDD.getFalse();
    }

    /**
     * Returns an NDD with a given constant value.
     */
    public NDD constant(int value) {
        NDD constant = NDD.createTerminal(value);
        return constant;
    }

    public NDD constant(double value) {
        NDD constant = NDD.createTerminal(value);
        return constant;
    }

    public void setCeil(int c) {
        ceil = c;
    }

    public NDD constant_ceil(double value) {
        if (value == 0) return readZero();
        double scale = Math.pow(10, ceil - 1 - (int)Math.floor(Math.log10(value)));
        NDD constant = NDD.createTerminal(Math.ceil(value * scale) / scale);
        return constant;
    }

    public int declareField(int bits){
        return NDD.declareField(bits);
    }

    /** Declare a field whose edge labels use the selected backend. */
    public int declareField(int bits, NDD.LabelMode mode) {
        return NDD.declareField(bits, mode);
    }

    /** Configure one backend before declaring the first field that uses it. */
    public void configureBackendCapacity(NDD.LabelMode mode, int tableSize, int cacheSize) {
        NDD.configureBackendCapacity(mode, tableSize, cacheSize);
    }

    public void generateFields() {
        NDD.generateFields();
    }

    public NDD ithVar(int field, int var) {
        return NDD.getVar(field, var);
    }

    public NDD nithVar(int field, int var) {
        return NDD.getNotVar(field, var);
    }

    // public NDD ithVar(int i) {
    //     return NDD.bddEngine.getVar(i);
    // }

    // public NDD ithVar(int i, NDD t, NDD e) {
    //     NDD ithVar = ithVar(i);
    //     NDD result = ithVar.ite(t, e);
    //     ithVar.recursiveDeref();
    //     return result;
    // }

    // private NDD backendIthVar(int i) {
    //     long ddNodePtr = getBackend().ithVar(ptr, i);
    //     return new NDD(ddNodePtr, this).withRef();
    // }

    // /**
    //  * Creates an NDD with a new variable at the largest existing index plus 1.
    //  * <p>
    //  * Only available in CUDD.
    //  *
    //  * @return created NDD
    //  */
    // public NDD newVar() {
    //     long ddNodePtr = getBackend().newVar(ptr);
    //     NDD result = new NDD(ddNodePtr, this).withRef();
    //     createVariableName(result);
    //     return result;
    // }

    // /**
    //  * Creates an NDD with a new variable at the given level and index of the largest existing index plus 1.
    //  * <p>
    //  * Only available in CUDD.
    //  *
    //  * @param level position of the variable
    //  * @return created NDD
    //  */
    // public NDD newVarAtLevel(int level) {
    //     long ddNodePtr = getBackend().newVarAtLevel(ptr, level);
    //     NDD result = new NDD(ddNodePtr, this).withRef();
    //     createVariableName(result);
    //     return result;
    // }

    public void printdot(String filename, NDD root, boolean bdd) {
            NDD.printDot(filename, root);
    }

    public void print(NDD root) {
        NDD.print(root);
    }

    public void quit() {
        
    }
}
