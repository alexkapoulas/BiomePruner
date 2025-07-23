package com.example.alexthundercook.biomepruner.util;

/**
 * Region key for cache indexing
 */
public record RegionKey(int x, int z) {
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof RegionKey other)) return false;
        return x == other.x && z == other.z;
    }

    @Override
    public int hashCode() {
        return 31 * x + z;
    }
}
