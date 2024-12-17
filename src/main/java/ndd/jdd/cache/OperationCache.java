package ndd.jdd.cache;

import com.sun.istack.internal.NotNull;

/**
 * Operation cache of NDD.
 * @author Zechun Li
 * @version 0.1
 */

public class OperationCache<T> {
    /**
     * The max number of entries in the cache.
     */
    int cacheSize;

    /**
     * The length of each entry. 3 for binary operations and 2 for unary operations.
     */
    int entrySize;

    /**
     * The cache.
     */
    Object[] cache;

    /**
     * Store the result of getEntry() temporarily, if the entry is found.
     */
    public T result;

    /**
     * Store the hash value of getEntry() temporarily, if the entry is not found.
     */
    public int hashValue;

    /**
     * Construct function of operation cache.
     * @param cacheSize The max number of entries in the cache
     * @param entrySize The length of each entry. 3 for binary operations and 2 for unary operations.
     */
    public OperationCache(int cacheSize, int entrySize) {
        this.cacheSize = cacheSize;
        this.entrySize = entrySize;
        cache = new Object [cacheSize * entrySize];
        result = null;
    }

    /**
     * Set the result of an entry.
     * @param index The index of the entry to be modified.
     * @param result The result to be cached.
     */
    private void setResult(int index, T result) {
        cache[index * entrySize] = result;
    }

    /**
     * Get the result of an entry.
     * @param index The index of the entry.
     * @return The cached result.
     */
    private T getResult(int index) {
        return (T) cache[index * entrySize];
    }

    /**
     * Set one of the operands of an entry.
     * @param index The index of the entry.
     * @param operandIndex The index of the operand in the entry.
     * @param operand The operand to be stored.
     */
    private void setOperand(int index, int operandIndex, T operand) {
        cache[index * entrySize + operandIndex] = operand;
    }

    /**
     * Get one of the operands of an entry.
     * @param index The index of the entry.
     * @param operandIndex The index of the operand in the entry.
     * @return The cached operand.
     */
    private  T getOperand(int index, int operandIndex) {
        return (T) cache[index * entrySize + operandIndex];
    }

    /**
     * Insert new entry of (operand1, result) into cache.
     * Directly overwrite the old value if there exist a hash collision.
     * @param index The index of the entry to be inserted, which is actually a hash value.
     * @param operand1 The only operand of a unary operation.
     * @param result The result of the operation.
     */
    public void setEntry(int index, T operand1, T result) {
        setOperand(index, 1, operand1);
        setResult(index, result);
    }

    /**
     * Insert new entry of (operand1, operand2, result) into cache.
     * Directly overwrite the old value if there exist a hash collision.
     * @param index The index of the entry to be inserted, which is actually a hash value.
     * @param operand1 The first operand of a binary operation.
     * @param operand2 The second operand of a binary operation.
     * @param result The result of the operation.
     */
    public void setEntry(int index, T operand1, T operand2, T result) {
        setOperand(index, 1, operand1);
        setOperand(index, 2, operand2);
        setResult(index, result);
    }

    /**
     * Get the result of operation(operand1).
     * @param operand1 The only operand of a unary operation.
     * @return TRUE if the entry found (the result will be stored in this.result), FALSE if the entry not found (the hashValue will be stored in this.hashValue).
     */
    public boolean getEntry(T operand1) {
        int hash = goodHash(operand1);
        if (getOperand(hash, 1) == operand1) {
            result = getResult(hash);
            return true;
        } else {
            hashValue = hash;
            return false;
        }
    }

    /**
     * Get the result of operation(operand1, operand2).
     * @param operand1 The first operand of a binary operation.
     * @param operand2 The second operand of a binary operation.
     * @return TRUE if the entry found (the result will be stored in this.result), FALSE if the entry not found (the hashValue will be stored in this.hashValue).
     */
    public boolean getEntry(T operand1, T operand2) {
        int hash = goodHash(operand1, operand2);
        if ((getOperand(hash, 1) == operand1 && getOperand(hash, 2) == operand2)
            || (getOperand(hash, 1) == operand2 && getOperand(hash, 2) == operand1)) {
            result = getResult(hash);
            return true;
        } else {
            hashValue = hash;
            return false;
        }
    }

    /**
     * Calculate the hash value of the operand, which will be the index in the cache.
     * @param operand1 The only operand of a unary operation.
     * @return The hash value.
     */
    private int goodHash(@NotNull T operand1) {
        return Math.abs(operand1.hashCode()) % cacheSize;
    }

    /**
     * Calculate the hash value of operands, which will be the index in the cache.
     * @param operand1 The first operand of a binary operation.
     * @param operand2 The second operand of a binary operation.
     * @return The hash value.
     */
    private int goodHash(@NotNull T operand1, @NotNull T operand2) {
        return (int) (Math.abs((long) operand1.hashCode() + (long) operand2.hashCode()) % cacheSize);
    }

    /**
     * Invalidate an entry in the cache.
     * @param index The index of the entry to be invalidated.
     */
    private void invalidateEntry(int index) {
        setOperand(index, 1, null);
    }

    /**
     * Check if the entry is valid.
     * @param index The index of the entry.
     * @return If the entry stores valid content.
     */
    private boolean isValid(int index) {
        return getOperand(index, 1) != null;
    }

    /**
     * Invalidate all the entries in the cache.
     */
    // invalidate all entries in the cache during garbage collections of the node table
    public void clearCache() {
        for (int i = 0; i < cacheSize; i++) {
            invalidateEntry(i);
        }
    }
}
