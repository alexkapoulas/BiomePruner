/* ExplorationContext.java */
package com.example.alexthundercook.biomepruner;

/**
 * Thread‑local re‑entry guard so we never recurse through our own mixin.
 */
public final class ExplorationContext {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private ExplorationContext() {}

    public static boolean active() { return ACTIVE.get(); }

    public static void enter() { ACTIVE.set(Boolean.TRUE); }
    public static void exit()  { ACTIVE.set(Boolean.FALSE); }
}
