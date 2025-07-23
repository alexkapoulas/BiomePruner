package com.example.alexthundercook.biomepruner.performance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tracks performance metrics for the BiomePruner mod
 */
public class PerformanceTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger("BiomePruner");
    private static final PerformanceTracker INSTANCE = new PerformanceTracker();

    // Metric sections
    public enum Section {
        TOTAL("Total Execution"),
        CACHE_CHECK("Cache Check"),
        HEIGHT_CALC("Height Calculation"),
        FLOOD_FILL("Flood Fill"),
        NEIGHBOR_SEARCH("Neighbor Search"),
        CACHE_STORE("Cache Store");

        private final String displayName;

        Section(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    // Metric storage
    private final ConcurrentHashMap<Section, MetricCollector> metrics = new ConcurrentHashMap<>();
    private final AtomicLong totalExecutions = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);

    // Time-based metric storage (for querying history)
    private final ConcurrentLinkedDeque<TimedMetric> timedMetrics = new ConcurrentLinkedDeque<>();
    private static final long MAX_HISTORY_MS = 300_000; // 5 minutes of history

    private PerformanceTracker() {
        // Initialize collectors for each section
        for (Section section : Section.values()) {
            metrics.put(section, new MetricCollector());
        }
    }

    public static PerformanceTracker getInstance() {
        return INSTANCE;
    }

    // Dynamic sampling - higher rate for rare operations like flood fills
    private static final int DEFAULT_SAMPLING_RATE = 100; // Track 1% of executions
    private static final int FLOOD_FILL_SAMPLING_RATE = 5; // Track 20% of flood fills (rare operation)
    private final AtomicLong sampleCounter = new AtomicLong(0);
    private final AtomicLong floodFillSampleCounter = new AtomicLong(0);

    /**
     * Start timing a section (with sampling to reduce overhead)
     */
    public Timer startTimer(Section section) {
        // Use higher sampling rate for flood fills since they're rare
        boolean shouldTrack;
        if (section == Section.FLOOD_FILL) {
            long count = floodFillSampleCounter.incrementAndGet();
            shouldTrack = (count % FLOOD_FILL_SAMPLING_RATE == 0);
        } else {
            long count = sampleCounter.incrementAndGet();
            shouldTrack = (count % DEFAULT_SAMPLING_RATE == 0);
        }
        
        return new Timer(section, shouldTrack);
    }

    /**
     * Record a cache hit
     */
    public void recordCacheHit() {
        cacheHits.incrementAndGet();
    }

    /**
     * Record a cache miss
     */
    public void recordCacheMiss() {
        cacheMisses.incrementAndGet();
    }

    /**
     * Timer for measuring execution time
     */
    public class Timer implements AutoCloseable {
        private final Section section;
        private final long startNanos;
        private final boolean shouldTrack;

        Timer(Section section, boolean shouldTrack) {
            this.section = section;
            this.shouldTrack = shouldTrack;
            this.startNanos = shouldTrack ? System.nanoTime() : 0;
        }

        @Override
        public void close() {
            // Always count total executions, but only record detailed metrics for sampled ones
            if (section == Section.TOTAL) {
                totalExecutions.incrementAndGet();
            }
            
            if (shouldTrack) {
                long durationNanos = System.nanoTime() - startNanos;
                recordMetric(section, durationNanos);
            }
        }

        public long getElapsedNanos() {
            return System.nanoTime() - startNanos;
        }
    }

    /**
     * Record a metric
     */
    private void recordMetric(Section section, long durationNanos) {
        MetricCollector collector = metrics.get(section);
        if (collector != null) {
            collector.record(durationNanos);

            // Record timed metric for history
            TimedMetric timed = new TimedMetric(
                    section,
                    durationNanos,
                    System.currentTimeMillis()
            );
            timedMetrics.offer(timed);

            // Clean old metrics
            cleanOldMetrics();
        }

        if (section == Section.TOTAL) {
            totalExecutions.incrementAndGet();
        }
    }

    /**
     * Clean metrics older than max history
     */
    private void cleanOldMetrics() {
        long cutoffTime = System.currentTimeMillis() - MAX_HISTORY_MS;
        while (!timedMetrics.isEmpty()) {
            TimedMetric oldest = timedMetrics.peekFirst();
            if (oldest != null && oldest.timestamp < cutoffTime) {
                timedMetrics.pollFirst();
            } else {
                break;
            }
        }
    }

    /**
     * Get performance statistics for a time range
     */
    public PerformanceStats getStats(int secondsBack) {
        long cutoffTime = System.currentTimeMillis() - (secondsBack * 1000L);

        // Filter metrics within time range
        List<TimedMetric> relevantMetrics = timedMetrics.stream()
                .filter(m -> m.timestamp >= cutoffTime)
                .collect(Collectors.toList());

        // Calculate stats for each section
        Map<Section, SectionStats> sectionStats = new HashMap<>();
        for (Section section : Section.values()) {
            List<Long> sectionDurations = relevantMetrics.stream()
                    .filter(m -> m.section == section)
                    .map(m -> m.durationNanos)
                    .sorted()
                    .collect(Collectors.toList());

            if (!sectionDurations.isEmpty()) {
                sectionStats.put(section, calculateStats(sectionDurations));
            }
        }

        // Calculate overall stats
        MetricCollector totalCollector = metrics.get(Section.TOTAL);
        long totalExecs = totalExecutions.get();
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        double hitRate = (hits + misses) > 0 ? (double) hits / (hits + misses) : 0.0;

        return new PerformanceStats(
                sectionStats,
                totalExecs,
                hitRate,
                relevantMetrics.size(),
                secondsBack
        );
    }

    /**
     * Calculate statistics for a list of durations
     */
    private SectionStats calculateStats(List<Long> sortedDurations) {
        if (sortedDurations.isEmpty()) {
            return new SectionStats(0L, 0L, 0L, 0L, 0L, 0L, 0);
        }

        // Calculate average
        long sum = sortedDurations.stream().mapToLong(Long::longValue).sum();
        long avg = sum / sortedDurations.size();

        // Calculate percentiles
        int size = sortedDurations.size();
        long p50 = sortedDurations.get(Math.max(0, size / 2 - 1));
        long p90 = sortedDurations.get(Math.max(0, (int)(size * 0.9) - 1));
        long p99 = sortedDurations.get(Math.max(0, (int)(size * 0.99) - 1));
        long max = sortedDurations.get(size - 1);
        long min = sortedDurations.get(0);

        return new SectionStats(avg, p50, p90, p99, max, min, size);
    }

    /**
     * Reset all metrics
     */
    public void reset() {
        for (MetricCollector collector : metrics.values()) {
            collector.reset();
        }
        totalExecutions.set(0);
        cacheHits.set(0);
        cacheMisses.set(0);
        timedMetrics.clear();
    }

    /**
     * Metric collector for a single section
     */
    private static class MetricCollector {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong(0);

        void record(long nanos) {
            count.increment();
            totalNanos.add(nanos);

            // Update max
            long currentMax;
            do {
                currentMax = maxNanos.get();
            } while (nanos > currentMax && !maxNanos.compareAndSet(currentMax, nanos));
        }

        void reset() {
            count.reset();
            totalNanos.reset();
            maxNanos.set(0);
        }

        long getCount() {
            return count.sum();
        }

        long getAverage() {
            long c = count.sum();
            return c > 0 ? totalNanos.sum() / c : 0;
        }

        long getMax() {
            return maxNanos.get();
        }
    }

    /**
     * Timed metric for history
     */
    private record TimedMetric(
            Section section,
            long durationNanos,
            long timestamp
    ) {}

    /**
     * Performance statistics
     */
    public record PerformanceStats(
            Map<Section, SectionStats> sectionStats,
            long totalExecutions,
            double cacheHitRate,
            int samplesInRange,
            int timeRangeSeconds
    ) {}

    /**
     * Statistics for a section
     */
    public record SectionStats(
            long avgNanos,
            long p50Nanos,
            long p90Nanos,
            long p99Nanos,
            long maxNanos,
            long minNanos,
            int sampleCount
    ) {
        public double avgMicros() { return avgNanos / 1000.0; }
        public double p50Micros() { return p50Nanos / 1000.0; }
        public double p90Micros() { return p90Nanos / 1000.0; }
        public double p99Micros() { return p99Nanos / 1000.0; }
        public double maxMicros() { return maxNanos / 1000.0; }
        public double minMicros() { return minNanos / 1000.0; }
    }
}