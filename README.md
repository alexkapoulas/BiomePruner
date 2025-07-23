# BiomePruner

A performance-focused Minecraft mod that eliminates micro biomes by replacing them with their dominant neighbors, improving world generation consistency and reducing biome fragmentation.

## Features

- **Micro Biome Detection**: Uses efficient flood fill algorithm to identify small biome patches
- **Smart Replacement**: Replaces micro biomes with the most common neighboring biome
- **Accurate Height Calculation**: Uses Minecraft's noise-based height system for precise surface detection
- **Configurable**: Extensive configuration options for threshold sizes and biome blacklists
- **Performance Optimized**: Multi-level caching system with C2ME compatibility
- **Thread Safe**: Designed for parallel chunk generation
- **Debug Messages**: Optional chat notifications showing biome replacements
- **Performance Tracking**: Built-in performance profiling with detailed metrics and analysis commands
- **Automated Testing**: Comprehensive testing system for biome replacement accuracy and performance validation

## Project Structure

```
BiomePruner/
├── src/
│   ├── CLAUDE.md                             # Source code documentation
│   └── main/
│       ├── java/com/example/alexthundercook/biomepruner/
│       │   ├── BiomePruner.java              # Main mod class
│       │   ├── cache/
│       │   │   ├── BiomeRegionCache.java     # Region-based biome cache
│       │   │   └── HeightmapCache.java       # Sparse heightmap cache
│       │   ├── command/
│       │   │   └── BiomePrunerCommands.java  # In-game commands
│       │   ├── config/
│       │   │   ├── BiomePrunerConfig.java    # Configuration spec
│       │   │   └── ConfigManager.java        # Config management
│       │   ├── core/
│       │   │   ├── BiomeSmoother.java        # Core processing logic
│       │   │   ├── BiomeAnalysis.java        # Biome analysis utilities
│       │   │   ├── ExecutionContext.java     # Thread-safe execution context
│       │   │   ├── GeneratorContext.java     # World generation context
│       │   │   ├── ChunkGeneratorAccess.java # Chunk generator utilities
│       │   │   └── MinimalHeightAccessor.java # Height calculation
│       │   ├── debug/
│       │   │   └── DebugMessenger.java       # Debug messaging system
│       │   ├── mixin/
│       │   │   ├── MultiNoiseBiomeSourceMixin.java    # Biome generation intercept
│       │   │   └── NoiseBasedChunkGeneratorMixin.java # Height calculation access
│       │   ├── performance/
│       │   │   └── PerformanceTracker.java   # Performance monitoring
│       │   ├── test/
│       │   │   ├── AutomatedTestManager.java # Automated testing system
│       │   │   ├── TestEventHandler.java     # Test lifecycle management
│       │   │   ├── BiomeTestResult.java      # Biome test result data
│       │   │   ├── PerformanceTestResult.java # Performance test data
│       │   │   └── TestReport.java           # Combined test report
│       │   └── util/
│       │       ├── Pos2D.java                # 2D position utilities
│       │       ├── LocalPos.java             # Local position utilities
│       │       └── RegionKey.java            # Region key utilities
│       └── resources/
│           ├── META-INF/
│           │   └── neoforge.mods.toml        # Mod metadata
│           ├── biomepruner.mixins.json       # Mixin configuration
│           └── pack.mcmeta                   # Resource pack metadata
├── claude_tooling/                            # Claude Code automation
│   ├── CLAUDE.md                             # Tooling documentation
│   ├── scripts/
│   │   ├── build_script.py                   # Build validation automation
│   │   └── run_automated_tests.py            # Testing automation
│   ├── build_output/                         # Build reports (ignored)
│   └── test_output/                          # Test results (ignored)
├── CLAUDE.md                                 # Development guide
├── build.gradle                              # Build configuration
├── gradle.properties                         # Build properties
└── README.md                                 # This file
```

## Building

### Standard Build
1. Ensure you have JDK 21 installed
2. Clone the repository
3. Run `./gradlew build`
4. Find the built jar in `build/libs/`

### For Development (with Claude Code)
Use the automated build validation script:
```bash
python claude_tooling/scripts/build_script.py
```

This provides intelligent error reporting and is the recommended approach for development.

## Configuration

The mod creates a configuration file at `config/biomepruner-common.toml` with the following options:

### General Settings
- `enabled` - Master toggle for the mod
- `microBiomeThreshold` - Size threshold for micro biomes (default: 50)
- `debug` - Enable debug logging

### Biome Blacklists
- `preservedBiomes` - Biomes that should never be removed
- `excludedAsReplacement` - Biomes that shouldn't be used as replacements
- `preserveOceanMonuments` - Preserve ocean biomes with monuments
- `preserveVillageBiomes` - Preserve village-spawning biomes

### Performance Settings
- `maxCacheMemoryMB` - Maximum cache memory usage
- `maxActiveRegions` - Number of active regions to keep in memory
- `gridSpacing` - Heightmap sampling grid spacing
- `debugMessages` - Show biome replacement messages in chat
- `performanceLogging` - Enable performance metric collection

### Testing Settings
- `automatedTestingEnabled` - Enable automated testing system
- `testCoordinates` - List of coordinates for biome replacement tests
- `performanceTestDuration` - Duration of performance stress tests
- `performanceTestSpeed` - Speed of player movement during performance tests
- `testResultsFile` - Output file for test results

## Commands

All commands require operator permission:

- `/biomepruner check [x y z]` - Analyze biome at position (defaults to player location)
- `/biomepruner performance` - Display performance metrics and reset counters

## Technical Details

### Algorithm Overview
1. Intercepts biome generation via Mixin
2. Projects position to surface using cached heightmap
3. Runs 2D flood fill to determine biome size
4. If size < threshold, finds dominant neighbor
5. Caches results for performance

### Key Design Features
- **Early Bailout**: Stops flood fill once threshold is exceeded
- **Collaborative Processing**: Multiple threads can work on same flood fill
- **Smart Caching**: Region-based cache with LRU eviction
- **Heightmap Optimization**: Sparse grid sampling with interpolation

## Testing

### Automated Testing
The mod includes a comprehensive automated testing system:

```bash
python claude_tooling/scripts/run_automated_tests.py
```

This script:
- Validates biome replacement accuracy at predefined coordinates
- Runs performance stress tests
- Generates detailed reports for analysis
- Automatically parses Minecraft logs for errors

### Manual Testing
For manual testing and debugging:
- Enable `debugMessages` in configuration
- Use `/biomepruner check [x y z]` to analyze specific positions
- Use `/biomepruner performance` to monitor performance metrics

## Compatibility

- **Minecraft**: 1.21.1
- **NeoForge**: 21.1.193  
- **Parchment Mappings**: 2024.11.17
- **Java**: 21+
- Designed to work with C2ME (concurrent chunk generation)

## Development

This project uses Claude Code for development automation:
- **Build validation**: Automated error detection and reporting
- **Testing**: Comprehensive biome replacement and performance testing
- **Documentation**: Contextual documentation for different parts of the codebase

See `CLAUDE.md` for development workflow and `src/CLAUDE.md` for source code documentation.

## License

All Rights Reserved