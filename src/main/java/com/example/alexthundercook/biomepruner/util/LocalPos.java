package com.example.alexthundercook.biomepruner.util;

/**
 * Local position within a region
 */
public record LocalPos(int x, int y, int z) {
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof LocalPos other)) return false;
        return x == other.x && y == other.y && z == other.z;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * x + y) + z;
    }
}