package com.example.alexthundercook.biomepruner.test;

import com.example.alexthundercook.biomepruner.performance.PerformanceTracker;

import java.util.HashMap;
import java.util.Map;

/**
 * Result of a performance stress test
 */
public record PerformanceTestResult(
        int duration,
        int blocksTraversed,
        PerformanceTracker.PerformanceStats stats
) {
    
    /**
     * Convert to JSON-like map for serialization
     */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();
        result.put("duration", duration);
        result.put("blocksTraversed", blocksTraversed);
        result.put("timestamp", new java.util.Date().toString());
        
        // Performance metrics
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalExecutions", stats.totalExecutions());
        metrics.put("cacheHitRate", stats.cacheHitRate());
        metrics.put("samplesInRange", stats.samplesInRange());
        
        // Section breakdown
        Map<String, Object> sections = new HashMap<>();
        for (var entry : stats.sectionStats().entrySet()) {
            var section = entry.getKey();
            var sectionStats = entry.getValue();
            
            Map<String, Object> sectionData = new HashMap<>();
            sectionData.put("sampleCount", sectionStats.sampleCount());
            sectionData.put("avgMicros", sectionStats.avgMicros());
            sectionData.put("p50Micros", sectionStats.p50Micros());
            sectionData.put("p90Micros", sectionStats.p90Micros());
            sectionData.put("p99Micros", sectionStats.p99Micros());
            sectionData.put("maxMicros", sectionStats.maxMicros());
            sectionData.put("minMicros", sectionStats.minMicros());
            
            sections.put(section.getDisplayName(), sectionData);
        }
        
        metrics.put("sections", sections);
        result.put("metrics", metrics);
        
        return result;
    }
}