package com.example.alexthundercook.biomepruner.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Complete test report aggregating all test results
 */
public record TestReport(
        long testRunId,
        String modVersion,
        List<BiomeTestResult> biomeTests,
        PerformanceTestResult performanceTest
) {
    
    /**
     * Convert to JSON string for output
     */
    public String toJson() {
        Map<String, Object> report = new HashMap<>();
        
        // Test run metadata
        report.put("testRunId", new java.util.Date(testRunId).toString());
        report.put("modVersion", modVersion);
        
        // Biome tests
        List<Map<String, Object>> biomeTestData = biomeTests.stream()
                .map(BiomeTestResult::toMap)
                .collect(Collectors.toList());
        report.put("biomeTests", biomeTestData);
        
        // Performance test
        if (performanceTest != null) {
            report.put("performanceTest", performanceTest.toMap());
        }
        
        // Summary
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalBiomeTests", biomeTests.size());
        summary.put("passedBiomeTests", biomeTests.stream().mapToInt(t -> t.passed() ? 1 : 0).sum());
        summary.put("failedBiomeTests", biomeTests.stream().mapToInt(t -> t.passed() ? 0 : 1).sum());
        summary.put("performanceTestCompleted", performanceTest != null);
        
        report.put("summary", summary);
        
        // Convert to JSON string (simple implementation)
        return toJsonString(report);
    }
    
    /**
     * Simple JSON serialization
     */
    private String toJsonString(Object obj) {
        if (obj == null) {
            return "null";
        }
        
        if (obj instanceof String) {
            return "\"" + ((String) obj).replace("\"", "\\\"") + "\"";
        }
        
        if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        }
        
        if (obj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) obj;
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(entry.getKey()).append("\":");
                sb.append(toJsonString(entry.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        
        if (obj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) obj;
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(",");
                sb.append(toJsonString(item));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        }
        
        return "\"" + obj.toString().replace("\"", "\\\"") + "\"";
    }
}