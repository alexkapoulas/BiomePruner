package com.example.alexthundercook.biomepruner.util;

/**
 * 2D position for surface operations
 */
public record Pos2D(int x, int z) {
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Pos2D other)) return false;
        return x == other.x && z == other.z;
    }

    @Override
    public int hashCode() {
        return 31 * x + z;
    }
}