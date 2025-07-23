package com.example.alexthundercook.biomepruner.test;

import java.util.Map;

/**
 * Result of a single biome test
 */
public record BiomeTestResult(
        int x, int y, int z,
        String expectedBiome,
        String actualBiome,
        boolean passed,
        Map<String, Object> analysis,
        long timestamp
) {
    
    /**
     * Convert to JSON-like map for serialization
     */
    public Map<String, Object> toMap() {
        return Map.of(
            "coordinates", Map.of("x", x, "y", y, "z", z),
            "expectedBiome", expectedBiome,
            "actualBiome", actualBiome,
            "passed", passed,
            "analysis", analysis != null ? analysis : Map.of(),
            "timestamp", new java.util.Date(timestamp).toString()
        );
    }
}