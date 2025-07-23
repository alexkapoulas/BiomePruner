package com.example.alexthundercook.biomepruner.test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.alexthundercook.biomepruner.BiomePruner;
import com.example.alexthundercook.biomepruner.config.ConfigManager;
import com.example.alexthundercook.biomepruner.core.BiomeAnalysis;
import com.example.alexthundercook.biomepruner.core.BiomeSmoother;
import com.example.alexthundercook.biomepruner.core.ChunkGeneratorAccess;
import com.example.alexthundercook.biomepruner.performance.PerformanceTracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages automated testing for BiomePruner
 */
public class AutomatedTestManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("BiomePruner-Test");
    private static final AutomatedTestManager INSTANCE = new AutomatedTestManager();
    
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final List<BiomeTestCase> testCases = new ArrayList<>();
    private final List<BiomeTestResult> biomeTestResults = new ArrayList<>();
    private PerformanceTestResult performanceTestResult;
    
    private TestState currentState = TestState.IDLE;
    private int currentTestIndex = 0;
    private long testStartTime;
    private ServerPlayer testPlayer;
    private BlockPos waitingForChunkPos;
    
    // Performance test state
    private BlockPos performanceTestStartPos;
    private long performanceTestStartTime;
    private int performanceTestBlocksTraveled;
    
    public enum TestState {
        IDLE,
        LOADING_TESTS,
        WAITING_FOR_PLAYER,
        TESTING_BIOME,
        WAITING_FOR_CHUNK,
        ANALYZING_FAILURE,
        PERFORMANCE_TEST,
        COMPLETED
    }
    
    private AutomatedTestManager() {}
    
    public static AutomatedTestManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * Start automated testing
     */
    public void startTesting() {
        if (!ConfigManager.isAutomatedTestingEnabled()) {
            LOGGER.info("Automated testing is disabled in config");
            return;
        }
        
        LOGGER.info("Starting BiomePruner automated testing");
        currentState = TestState.LOADING_TESTS;
        testStartTime = System.currentTimeMillis();
        
        // Load test cases from config
        loadTestCases();
        
        // Schedule test execution
        scheduler.schedule(this::waitForPlayer, 5, TimeUnit.SECONDS);
    }
    
    /**
     * Load test cases from configuration
     */
    private void loadTestCases() {
        if (!ConfigManager.areBiomeTestsEnabled()) {
            LOGGER.info("Biome tests disabled, skipping test case loading");
            testCases.clear();
            return;
        }
        
        List<? extends String> testCoords = ConfigManager.getTestCoordinates();
        
        for (String coordStr : testCoords) {
            try {
                String[] parts = coordStr.split(",");
                if (parts.length == 4) {
                    int x = Integer.parseInt(parts[0].trim());
                    int y = Integer.parseInt(parts[1].trim());
                    int z = Integer.parseInt(parts[2].trim());
                    String expectedBiome = parts[3].trim();
                    
                    testCases.add(new BiomeTestCase(x, y, z, expectedBiome));
                    LOGGER.info("Loaded test case: {} at {},{},{}", expectedBiome, x, y, z);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to parse test coordinate: {}", coordStr, e);
            }
        }
        
        LOGGER.info("Loaded {} test cases", testCases.size());
    }
    
    /**
     * Wait for a player to join the server
     */
    private void waitForPlayer() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            scheduler.schedule(this::waitForPlayer, 1, TimeUnit.SECONDS);
            return;
        }
        
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            LOGGER.info("Waiting for player to join...");
            scheduler.schedule(this::waitForPlayer, 1, TimeUnit.SECONDS);
            return;
        }
        
        testPlayer = players.get(0);
        LOGGER.info("Player found: {}", testPlayer.getName().getString());
        
        // Check what tests to run
        if (ConfigManager.areBiomeTestsEnabled() && !testCases.isEmpty()) {
            currentState = TestState.TESTING_BIOME;
            LOGGER.info("Starting biome tests");
            runNextBiomeTest();
        } else if (ConfigManager.arePerformanceTestsEnabled()) {
            currentState = TestState.PERFORMANCE_TEST;
            LOGGER.info("Starting performance test (biome tests disabled or no test cases)");
            startPerformanceTest();
        } else {
            currentState = TestState.COMPLETED;
            LOGGER.info("No tests enabled, completing immediately");
            writeTestResults();
        }
    }
    
    /**
     * Run the next biome test
     */
    private void runNextBiomeTest() {
        if (currentTestIndex >= testCases.size()) {
            // All biome tests complete, check if performance test should run
            if (ConfigManager.arePerformanceTestsEnabled()) {
                LOGGER.info("All biome tests complete, starting performance test");
                currentState = TestState.PERFORMANCE_TEST;
                startPerformanceTest();
            } else {
                LOGGER.info("All biome tests complete, performance tests disabled");
                currentState = TestState.COMPLETED;
                writeTestResults();
                LOGGER.info("All tests completed!");
            }
            return;
        }
        
        BiomeTestCase testCase = testCases.get(currentTestIndex);
        LOGGER.info("Running biome test {}/{}: {} at {},{},{}", 
            currentTestIndex + 1, testCases.size(), 
            testCase.expectedBiome, testCase.x, testCase.y, testCase.z);
        
        // Teleport player to test location
        BlockPos testPos = new BlockPos(testCase.x, testCase.y, testCase.z);
        ServerLevel level = testPlayer.serverLevel();
        
        // Position player 128 blocks above sea level looking straight down
        int seaLevel = level.getSeaLevel();
        int testY = seaLevel + 128;
        
        testPlayer.teleportTo(level, testCase.x + 0.5, testY, testCase.z + 0.5, 
            testPlayer.getYRot(), 90.0f); // 90° pitch = looking straight down
        
        // Set state to wait for chunk
        currentState = TestState.WAITING_FOR_CHUNK;
        waitingForChunkPos = testPos;
        
        // Schedule chunk check
        scheduler.schedule(() -> checkChunkLoaded(testCase), 1, TimeUnit.SECONDS);
    }
    
    /**
     * Check if chunk is loaded and run test
     */
    private void checkChunkLoaded(BiomeTestCase testCase) {
        ServerLevel level = testPlayer.serverLevel();
        BlockPos pos = new BlockPos(testCase.x, testCase.y, testCase.z);
        
        // Check if chunk is loaded at full status
        if (level.isLoaded(pos) && level.getChunk(pos).getPersistedStatus().isOrAfter(net.minecraft.world.level.chunk.status.ChunkStatus.FULL)) {
            LOGGER.info("Chunk loaded at {},{},{}, running biome test", testCase.x, testCase.y, testCase.z);
            currentState = TestState.TESTING_BIOME;
            runBiomeTest(testCase);
        } else {
            // Retry after delay
            LOGGER.debug("Waiting for chunk to load at {},{},{}", testCase.x, testCase.y, testCase.z);
            scheduler.schedule(() -> checkChunkLoaded(testCase), 1, TimeUnit.SECONDS);
        }
    }
    
    /**
     * Run the actual biome test
     */
    private void runBiomeTest(BiomeTestCase testCase) {
        ServerLevel level = testPlayer.serverLevel();
        
        // Get the chunk generator and biome source
        var chunkGen = level.getChunkSource().getGenerator();
        if (!(chunkGen instanceof NoiseBasedChunkGenerator noiseGen)) {
            LOGGER.error("Invalid chunk generator type");
            recordBiomeTestFailure(testCase, "Invalid chunk generator", null);
            moveToNextTest();
            return;
        }
        
        if (!(noiseGen.getBiomeSource() instanceof MultiNoiseBiomeSource biomeSource)) {
            LOGGER.error("Invalid biome source type");
            recordBiomeTestFailure(testCase, "Invalid biome source", null);
            moveToNextTest();
            return;
        }
        
        // Get climate sampler
        var climateSampler = ((ChunkGeneratorAccess)noiseGen).biomepruner$getClimateSampler();
        if (climateSampler == null) {
            LOGGER.error("Unable to access climate sampler");
            recordBiomeTestFailure(testCase, "No climate sampler", null);
            moveToNextTest();
            return;
        }
        
        // Get the biome at the test position
        Holder<Biome> actualBiome = biomeSource.getNoiseBiome(
            testCase.x >> 2, testCase.y >> 2, testCase.z >> 2, climateSampler);
        
        String actualBiomeName = getBiomeName(actualBiome);
        boolean passed = testCase.expectedBiome.equals(actualBiomeName);
        
        if (passed) {
            LOGGER.info("Test PASSED: Expected {} and got {} at {},{},{}", 
                testCase.expectedBiome, actualBiomeName, testCase.x, testCase.y, testCase.z);
            recordBiomeTestSuccess(testCase, actualBiomeName);
        } else {
            LOGGER.warn("Test FAILED: Expected {} but got {} at {},{},{}", 
                testCase.expectedBiome, actualBiomeName, testCase.x, testCase.y, testCase.z);
            
            // Run analysis for failed test
            currentState = TestState.ANALYZING_FAILURE;
            BiomeAnalysis analysis = BiomeSmoother.getInstance().analyzeBiomeWithContext(
                testCase.x, testCase.y, testCase.z, actualBiome, biomeSource, climateSampler);
            
            recordBiomeTestFailure(testCase, actualBiomeName, analysis);
        }
        
        moveToNextTest();
    }
    
    /**
     * Move to the next test
     */
    private void moveToNextTest() {
        currentTestIndex++;
        scheduler.schedule(this::runNextBiomeTest, 2, TimeUnit.SECONDS);
    }
    
    /**
     * Start performance stress test
     */
    private void startPerformanceTest() {
        LOGGER.info("Starting performance stress test");
        
        // Reset performance tracker
        PerformanceTracker.getInstance().reset();
        
        // Parse starting coordinates from config
        String coordsStr = ConfigManager.getPerformanceTestStartCoords();
        try {
            String[] parts = coordsStr.split(",");
            if (parts.length == 3) {
                int x = Integer.parseInt(parts[0].trim());
                int y = Integer.parseInt(parts[1].trim());
                int z = Integer.parseInt(parts[2].trim());
                
                // Set starting position from config
                performanceTestStartPos = new BlockPos(x, y, z);
                
                // Teleport player to starting position
                ServerLevel level = testPlayer.serverLevel();
                int safeY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) + 2;
                // Calculate player rotation based on direction
                String direction = ConfigManager.getPerformanceTestDirection().toUpperCase();
                float yRot = switch (direction) {
                    case "NORTH" -> 180.0f;  // Face North
                    case "SOUTH" -> 0.0f;    // Face South
                    case "EAST" -> 270.0f;   // Face East
                    case "WEST" -> 90.0f;    // Face West
                    default -> 270.0f;       // Default to East
                };
                
                // Calculate view pitch based on height above sea level
                int seaLevel = level.getSeaLevel();
                float heightAboveSeaLevel = safeY - seaLevel;
                float pitch = calculateViewPitch(heightAboveSeaLevel);
                
                testPlayer.teleportTo(level, x + 0.5, safeY, z + 0.5, yRot, pitch);
                
                LOGGER.info("Performance test starting at configured coordinates: {},{},{}", x, safeY, z);
            } else {
                // Fallback to current position
                performanceTestStartPos = testPlayer.blockPosition();
                LOGGER.warn("Invalid performance test coordinates format: {}, using current position", coordsStr);
            }
        } catch (Exception e) {
            // Fallback to current position
            performanceTestStartPos = testPlayer.blockPosition();
            LOGGER.error("Failed to parse performance test coordinates: {}, using current position", coordsStr, e);
        }
        
        performanceTestStartTime = System.currentTimeMillis();
        performanceTestBlocksTraveled = 0;
        
        // Start movement
        movePlayerForPerformanceTest();
    }
    
    /**
     * Move player for performance test
     */
    private void movePlayerForPerformanceTest() {
        long elapsedSeconds = (System.currentTimeMillis() - performanceTestStartTime) / 1000;
        int testDuration = ConfigManager.getPerformanceTestDuration();
        
        if (elapsedSeconds >= testDuration) {
            // Test complete
            completePerformanceTest();
            return;
        }
        
        // Calculate next position
        int speed = ConfigManager.getPerformanceTestSpeed();
        ServerLevel level = testPlayer.serverLevel();
        
        // Get movement direction from config
        String direction = ConfigManager.getPerformanceTestDirection().toUpperCase();
        BlockPos currentPos = testPlayer.blockPosition();
        
        // Calculate offset based on direction (very small increments for ultra-smooth movement)
        double moveDistance = speed * 0.05; // Move in 5% increments for ultra-smooth visual
        double xOffset = 0, zOffset = 0;
        switch (direction) {
            case "NORTH" -> zOffset = -moveDistance;  // Negative Z is North
            case "SOUTH" -> zOffset = moveDistance;   // Positive Z is South
            case "EAST" -> xOffset = moveDistance;    // Positive X is East
            case "WEST" -> xOffset = -moveDistance;   // Negative X is West
            default -> {
                LOGGER.warn("Invalid performance test direction: {}, defaulting to EAST", direction);
                xOffset = moveDistance;
            }
        }
        
        BlockPos nextPos = new BlockPos((int)(currentPos.getX() + xOffset), currentPos.getY(), (int)(currentPos.getZ() + zOffset));
        
        // Use fixed Y from start position
        int fixedY = performanceTestStartPos.getY();
        
        // Teleport with consistent facing direction
        String facingDirection = ConfigManager.getPerformanceTestDirection().toUpperCase();
        float yRot = switch (facingDirection) {
            case "NORTH" -> 180.0f;  // Face North
            case "SOUTH" -> 0.0f;    // Face South
            case "EAST" -> 270.0f;   // Face East
            case "WEST" -> 90.0f;    // Face West
            default -> 270.0f;       // Default to East
        };
        
        // Calculate view pitch based on height above sea level
        int seaLevel = level.getSeaLevel();
        float heightAboveSeaLevel = fixedY - seaLevel;
        float pitch = calculateViewPitch(heightAboveSeaLevel);
        
        testPlayer.teleportTo(level, nextPos.getX() + 0.5, fixedY, nextPos.getZ() + 0.5,
            yRot, pitch);
        
        performanceTestBlocksTraveled += moveDistance;
        
        // Schedule next movement with very short interval for ultra-smooth movement
        scheduler.schedule(this::movePlayerForPerformanceTest, 50, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Calculate view pitch based on height above sea level
     * @param heightAboveSeaLevel Height in blocks above sea level
     * @return Pitch angle in degrees (positive = looking down)
     */
    private float calculateViewPitch(float heightAboveSeaLevel) {
        // Clamp height to reasonable range
        heightAboveSeaLevel = Math.max(0, Math.min(heightAboveSeaLevel, 128));
        
        // Linear interpolation: 0 blocks = 0° pitch, 128 blocks = 45° pitch  
        // Maximum pitch occurs at 128 blocks above sea level
        float maxPitch = 45.0f;
        float maxHeight = 128.0f;
        
        return (heightAboveSeaLevel / maxHeight) * maxPitch;
    }
    
    /**
     * Complete performance test
     */
    private void completePerformanceTest() {
        LOGGER.info("Performance test complete");
        
        // Get performance stats
        PerformanceTracker.PerformanceStats stats = PerformanceTracker.getInstance()
            .getStats(ConfigManager.getPerformanceTestDuration());
        
        performanceTestResult = new PerformanceTestResult(
            ConfigManager.getPerformanceTestDuration(),
            performanceTestBlocksTraveled,
            stats
        );
        
        currentState = TestState.COMPLETED;
        
        // Write results
        writeTestResults();
        
        LOGGER.info("All tests completed!");
    }
    
    /**
     * Write test results to file
     */
    private void writeTestResults() {
        TestReport report = new TestReport(
            testStartTime,
            BiomePruner.MOD_ID,
            biomeTestResults,
            performanceTestResult
        );
        
        String json = report.toJson();
        
        try {
            Path resultsPath = Path.of(ConfigManager.getTestResultsFile());
            Files.writeString(resultsPath, json);
            LOGGER.info("Test results written to: {}", resultsPath.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Failed to write test results", e);
        }
    }
    
    /**
     * Record successful biome test
     */
    private void recordBiomeTestSuccess(BiomeTestCase testCase, String actualBiome) {
        biomeTestResults.add(new BiomeTestResult(
            testCase.x, testCase.y, testCase.z,
            testCase.expectedBiome,
            actualBiome,
            true,
            null,
            System.currentTimeMillis()
        ));
    }
    
    /**
     * Record failed biome test
     */
    private void recordBiomeTestFailure(BiomeTestCase testCase, String actualBiome, BiomeAnalysis analysis) {
        Map<String, Object> analysisData = null;
        
        if (analysis != null) {
            analysisData = new HashMap<>();
            analysisData.put("surfaceY", analysis.surfaceY());
            analysisData.put("vanillaBiome", analysis.vanillaBiomeName());
            analysisData.put("surfaceBiome", analysis.surfaceBiomeName());
            analysisData.put("regionSize", analysis.regionSize());
            analysisData.put("isMicroBiome", analysis.isMicroBiome());
            analysisData.put("replacement", analysis.replacementBiomeName());
            analysisData.put("isPreserved", analysis.isPreserved());
            analysisData.put("matchesSurface", analysis.matchesSurface());
        }
        
        biomeTestResults.add(new BiomeTestResult(
            testCase.x, testCase.y, testCase.z,
            testCase.expectedBiome,
            actualBiome,
            false,
            analysisData,
            System.currentTimeMillis()
        ));
    }
    
    /**
     * Get biome name from holder
     */
    private String getBiomeName(Holder<Biome> biomeHolder) {
        if (!biomeHolder.isBound()) {
            return "unknown";
        }
        
        return biomeHolder.unwrapKey()
            .map(key -> key.location().toString())
            .orElse("unknown");
    }
    
    /**
     * Shutdown the test manager
     */
    public void shutdown() {
        scheduler.shutdown();
    }
    
    /**
     * Get current test state
     */
    public TestState getCurrentState() {
        return currentState;
    }
    
    /**
     * Test case data
     */
    private static class BiomeTestCase {
        final int x, y, z;
        final String expectedBiome;
        
        BiomeTestCase(int x, int y, int z, String expectedBiome) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.expectedBiome = expectedBiome;
        }
    }
}