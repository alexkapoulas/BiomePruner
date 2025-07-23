# BiomePruner

A performance-focused Minecraft mod that eliminates micro biomes by replacing them with their dominant neighbors, improving world generation consistency and reducing biome fragmentation.

## Features

- **Micro Biome Detection**: Uses efficient flood fill algorithm to identify small biome patches
- **Smart Replacement**: Replaces micro biomes with the most common neighboring biome
- **Accurate Height Calculation**: Uses Minecraft's noise-based height system for precise surface detection
- **Configurable**: Extensive configuration options for threshold sizes and biome blacklists
- **Performance Optimized**: Multi-level caching system with C2ME compatibility
- **Thread Safe**: Designed for parallel chunk generation
- **Debug Messages**: Optional chat notifications showing biome replacements with teleport functionality
- **Performance Tracking**: Built-in performance profiling with detailed metrics and analysis commands

## Project Structure

```
biomepruner/
├── src/
│   ├── main/
│   │   ├── java/com/example/alexthundercook/
│   │   │   ├── BiomePruner.java              # Main mod class
│   │   │   ├── cache/
│   │   │   │   ├── BiomeRegionCache.java     # Region-based biome cache
│   │   │   │   └── HeightmapCache.java       # Sparse heightmap cache
│   │   │   ├── command/
│   │   │   │   └── BiomePrunerCommands.java  # Mod commands
│   │   │   ├── config/
│   │   │   │   ├── BiomePrunerConfig.java    # Configuration spec
│   │   │   │   └── ConfigManager.java        # Config management
│   │   │   ├── core/
│   │   │   │   ├── BiomeSmoother.java        # Core processing logic
│   │   │   │   ├── ExecutionContext.java     # Recursion prevention
│   │   │   │   ├── GeneratorContext.java     # Height calculation
│   │   │   │   ├── MinimalHeightAccessor.java # Height accessor
│   │   │   │   ├── ChunkGeneratorAccess.java # Generator interface
│   │   │   │   └── BiomeSourceAccess.java    # Biome source interface
│   │   │   ├── debug/
│   │   │   │   └── DebugMessenger.java       # Debug chat messages
│   │   │   ├── mixin/
│   │   │   │   ├── MultiNoiseBiomeSourceMixin.java      # Biome generation
│   │   │   │   ├── MultiNoiseBiomeSourceAccessMixin.java # Biome access
│   │   │   │   └── NoiseBasedChunkGeneratorMixin.java   # Generator access
│   │   │   ├── performance/
│   │   │   │   └── PerformanceTracker.java   # Performance metrics
│   │   │   └── util/
│   │   │       ├── Pos2D.java                # 2D position record
│   │   │       ├── LocalPos.java             # Local position record
│   │   │       └── RegionKey.java            # Region key record
│   │   └── resources/
│   │       ├── META-INF/
│   │       │   └── neoforge.mods.toml        # Mod metadata
│   │       ├── biomepruner.mixins.json       # Mixin configuration
│   │       └── pack.mcmeta                   # Resource pack metadata
├── build.gradle                               # Build configuration
├── gradle.properties                          # Build properties
├── settings.gradle                            # Gradle settings
└── README.md                                  # This file
```

## Building

1. Ensure you have JDK 21 installed
2. Clone the repository
3. Run `./gradlew build`
4. Find the built jar in `build/libs/`

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

## Commands

All commands require operator permission:

- `/biomepruner stats` - Show mod status and configuration
- `/biomepruner check [x y z]` - Analyze biome at position (defaults to player location)
- `/biomepruner performance [seconds]` - Display performance metrics
- `/biomepruner reset` - Clear performance data
- `/biomepruner teleport <x> <z>` - Teleport to coordinates (used by debug messages)

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

## Compatibility

- Minecraft: 1.21.1
- NeoForge: 21.1.193
- Designed to work with C2ME (concurrent chunk generation)

## License

MIT License - See LICENSE file for details