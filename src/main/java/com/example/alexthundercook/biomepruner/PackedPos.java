/* PackedPos.java */
package com.example.alexthundercook.biomepruner;

/**
 * Packs a pair of quart X/Z coordinates into a single {@code long}.
 * Uses the upper 32 bits for X and the lower 32 bits for Z.
 */
public final class PackedPos {
    private PackedPos() {}

    public static long pack(int qx, int qz) {
        return ((long) qx << 32) | (qz & 0xFFFFFFFFL);
    }

    public static int unpackX(long packed) { return (int) (packed >> 32); }
    public static int unpackZ(long packed) { return (int)  packed;       }
}
