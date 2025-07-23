# BiomePruner Source Code Guide

## Core Concept

BiomePruner eliminates micro biomes (small isolated biome patches) by replacing them with their dominant neighboring biome. It hooks into world generation using Mixins to intercept biome queries.

## Design Priorities

1. **Biome Replacement Accuracy** - Top priority. The mod must correctly identify and replace micro biomes
2. **Performance** - Secondary priority. Optimizations should never compromise accuracy

## Main Entry Point

- **`BiomePruner.java`**: Main mod class that handles NeoForge initialization, configuration registration, command registration, and event bus setup

## Package Structure

### `mixin/` - Minecraft Integration
- **`MultiNoiseBiomeSourceMixin`**: Intercepts biome generation calls during world generation
- **`NoiseBasedChunkGeneratorMixin`**: Provides access to height calculation

**Key Pattern**: Mixins are kept minimal and delegate to regular classes. Use `@Unique` prefix for added methods.

**Critical Design Rule**: The `getNoiseBiome` mixin must ALWAYS return the correct biome and NEVER yield to vanilla. If we yield to vanilla thinking it can be fixed by a later flood fill, the biome data might become baked before that happens. This is a fundamental design choice that should never be changed.

### `core/` - Main Algorithm
- **`BiomeSmoother`**: Singleton class with main algorithm that determines if a biome is micro and finds replacements
- **`BiomeAnalysis`**: Analysis utilities for biome evaluation
- **`ExecutionContext`**: Thread-safe execution context management
- **`GeneratorContext`**: World generation context handling
- **`ChunkGeneratorAccess`**: Access utilities for chunk generator functionality
- **`MinimalHeightAccessor`**: Height calculation utilities

**Key Pattern**: Uses flood fill algorithm on 2D surface projection with early bailout when threshold is exceeded.

### `cache/` - Performance Optimization
- **`BiomeRegionCache`**: Region-based cache (512x512 blocks) with LRU eviction
- **`HeightmapCache`**: Sparse grid sampling with configurable spacing and interpolation

**Key Pattern**: Thread-safe with collaborative processing support, designed for C2ME compatibility.

### `config/` - Configuration System
- **`BiomePrunerConfig`**: NeoForge ConfigSpec-based configuration definitions
- **`ConfigManager`**: Static access to config values with caching

**Configuration Categories**:
- General settings (enabled, threshold, debug)
- Biome blacklists (preserved, excluded)
- Performance settings (cache sizes, grid spacing)
- Debug options (messages, performance logging)
- Testing settings (automated testing configuration)

### `command/` - In-Game Commands
- **`BiomePrunerCommands`**: Command registration and handlers

**Available Commands**:
- `/biomepruner check [x y z]` - Analyze a specific position
- `/biomepruner performance` - Check performance metrics

### `test/` - Automated Testing System
- **`AutomatedTestManager`**: Singleton manager for automated testing with state machine
- **`TestEventHandler`**: Event handler for test lifecycle management
- **`BiomeTestResult`**: Data structure for biome test results
- **`PerformanceTestResult`**: Data structure for performance test results
- **`TestReport`**: Combined test report data structure

**Key Pattern**: Automated testing runs when `automatedTestingEnabled` config is true, validates biome replacement at specific coordinates and runs performance tests.

### `debug/` - Development Tools
- **`DebugMessenger`**: Debug message system for development feedback

### `performance/` - Performance Monitoring
- **`PerformanceTracker`**: Performance monitoring and timing breakdown

### `util/` - Utilities
- **`LocalPos`**: Local position utilities
- **`Pos2D`**: 2D position handling
- **`RegionKey`**: Region-based caching keys

## Processing Flow
1. Minecraft requests biome at position (x,y,z)
2. Mixin intercepts call and passes to BiomeSmoother
3. Position is projected to surface using heightmap cache
4. Flood fill determines biome size at surface
5. If size < threshold, find dominant neighbor
6. Result is cached and returned

## Development Guidelines

### Thread Safety
- Multiple threads can collaborate on same flood fill task
- Cache operations are atomic with proper locking
- All shared state is properly synchronized

### Performance Considerations
- Heightmap cache reduces expensive height calculations
- Region cache prevents redundant flood fills
- Early bailout in flood fill when threshold exceeded
- Configurable memory limits and cache sizes

### Adding New Features
- Extend core algorithm in `BiomeSmoother`
- Cache computed data in appropriate cache classes
- Add config options to `BiomePrunerConfig` for user control
- Keep mixin methods minimal - delegate to regular classes

### Debugging
- Enable `debug` and `debugMessages` in config for detailed logging
- Use `/biomepruner check` command to analyze specific positions
- Performance tracker provides timing breakdown by section
- Test with other mods that might also mixin to the same classes