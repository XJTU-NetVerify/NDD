package ndd.cache;

import com.sun.istack.internal.NotNull;

import java.util.ArrayList;

public class OperationCache<T> {
    // The max number of entries in the cache.
    int cacheSize;
    // The length of each entry. 3 for binary operations and 2 for unary operations.
    int entrySize;
    Object[] cache;
    // Store the result of getEntry() temporarily
    public T result;
    public int hashValue;

    public OperationCache(int cacheSize, int entrySize) {
        this.cacheSize = cacheSize;
        this.entrySize = entrySize;
        cache = new Object [cacheSize * entrySize];
        result = null;
    }

    private void setResult(int index, T result) {
        cache[index * entrySize] = result;
    }

    private T getResult(int index) {
        return (T) cache[index * entrySize];
    }

    private void setOperand(int index, int operandIndex, T operand) {
        cache[index * entrySize + operandIndex] = operand;
    }

    private  T getOperand(int index, int operandIndex) {
        return (T) cache[index * entrySize + operandIndex];
    }

    /*
     * insert new entry of <operand1> -> result into cache
     * directly overwrite the old value if there exist a hash collision
     */

    public void setEntry(int index, T operand1, T result) {
        setOperand(index, 1, operand1);
        setResult(index, result);
    }

    public void setEntry(int index, T operand1, T operand2, T result) {
        setOperand(index, 1, operand1);
        setOperand(index, 2, operand2);
        setResult(index, result);
    }

    /*
     * get the result of operation(operand1)
     * return true if the entry found (the result will be stored in this.result)
     * return false if the entry not found (the hashValue will be stored in this.hashValue)
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

    private int goodHash(@NotNull T operand1) {
        return Math.abs(operand1.hashCode()) % cacheSize;
    }

    private int goodHash(@NotNull T operand1, @NotNull T operand2) {
        return (int) (Math.abs((long) operand1.hashCode() + (long) operand2.hashCode()) % cacheSize);
    }

    private void invalidateEntry(int index) {
        setOperand(index, 1, null);
    }

    private boolean isValid(int index) {
        return getOperand(index, 1) != null;
    }

    // invalidate all entries in the cache during garbage collections of the node table
    public void clearCache() {
        for (int i = 0; i < cacheSize; i++) {
            invalidateEntry(i);
        }
    }
}
