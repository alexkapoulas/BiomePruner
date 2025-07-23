package com.example.alexthundercook.biomepruner.core;

/**
 * Thread-safe execution context to prevent infinite recursion in mixins
 */
public class ExecutionContext {
    // Thread-local flag to track if we're already in mod code
    private static final ThreadLocal<Boolean> IN_MOD_CODE = ThreadLocal.withInitial(() -> false);

    /**
     * Check if current thread is already executing mod code
     */
    public static boolean isInModCode() {
        return IN_MOD_CODE.get();
    }

    /**
     * Mark that we're entering mod code
     */
    public static void enter() {
        IN_MOD_CODE.set(true);
    }

    /**
     * Mark that we're exiting mod code
     */
    public static void exit() {
        IN_MOD_CODE.set(false);
    }

    /**
     * Execute a function within the mod context
     */
    public static <T> T executeInContext(java.util.function.Supplier<T> supplier) {
        if (isInModCode()) {
            // Already in context, just execute
            return supplier.get();
        }

        try {
            enter();
            return supplier.get();
        } finally {
            exit();
        }
    }
}