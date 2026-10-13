package org.ants.jndd.parallel;

/** Shared admission gate for the Boolean and multi-terminal JNI managers. */
final class NativeEngineAdmission {
    static Object active;
    private NativeEngineAdmission() {}
}
