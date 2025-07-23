# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Validation Process

After making any modifications to source files in this Minecraft mod project, you MUST validate your changes using the automated build script before proceeding.

### Required Workflow

1. **Complete your source file modifications**
2. **Run the build validation script**:
   ```bash
   python build_script.py
   ```
3. **Check the results**:
   - If build succeeds (exit code 0): Continue with your task
   - If build fails (exit code 1): Fix the errors and repeat

### Understanding Build Failures

When the build fails, the script generates two files:

1. **`build_error_report.md`** - Human-readable error report with:
   - Error locations (file and line number)
   - Error messages and context
   - Relevant source code snippets with the error line highlighted

2. **`build_errors.json`** - Structured error data:
   ```json
   {
     "success": false,
     "error_count": 2,
     "errors": [
       {
         "file": "src/main/java/com/example/MyMod.java",
         "line": 42,
         "message": "cannot find symbol",
         "context": "  symbol: class NonExistentClass",
         "type": "compilation"
       }
     ]
   }
   ```

### Error Resolution Process

When you encounter build errors:

1. **Read the error report** to understand what went wrong
2. **Examine the highlighted source code** in the report
3. **Fix the specific errors** in the source files
4. **Run the build script again** to validate your fixes
5. **Repeat until the build succeeds**

### Common Error Types

- **Compilation errors**: Missing imports, undefined symbols, syntax errors
- **Gradle errors**: Build configuration issues, dependency problems
- **General errors**: Other build failures (check the full message)

### Important Notes

- The script automatically finds and uses `gradlew` (or `gradlew.bat` on Windows)
- Build timeout is set to 5 minutes
- The script searches multiple directory structures common in Minecraft mods:
  - Standard: `src/main/java`, `src/test/java`
  - Multi-loader: `common/src/main/java`, `forge/src/main/java`, `fabric/src/main/java`
  - Additional: `src/client/java`, `src/api/java`, `src/main/kotlin`

## Common Development Commands

### Build Commands
```bash
# Build the mod
./gradlew build

# Quick build and deploy to Prism Launcher instance
./gradlew buildAndRun

# Deploy to Prism Launcher without running
./gradlew deployToPrism

# Clear dev world regions (preserves spawn)
./gradlew clearDevRegion

# Run Minecraft client for testing
./gradlew runClient

# Run Minecraft server for testing
./gradlew runServer

# Generate mod data
./gradlew runData

# Clean build artifacts
./gradlew clean
```

### Testing a Single Feature
When testing biome smoothing:
1. Use `/biomepruner check [x y z]` to analyze a specific position
2. Enable debug messages in config for live replacement notifications
3. Use `/biomepruner performance` to check performance metrics

## Architecture Overview

### Core Concept
BiomePruner is a Minecraft mod that eliminates micro biomes (small isolated biome patches) by replacing them with their dominant neighboring biome. It hooks into world generation using Mixins to intercept biome queries.

### Key Components

1. **Mixin Integration** (`mixin/` package)
   - `MultiNoiseBiomeSourceMixin`: Intercepts biome generation calls
   - `NoiseBasedChunkGeneratorMixin`: Provides access to height calculation
   - The mod operates by intercepting `getBiome()` calls during world generation

2. **Core Processing** (`core/` package)
   - `BiomeSmoother`: Main algorithm that determines if a biome is micro and finds replacements
   - Uses flood fill algorithm on 2D surface projection
   - Early bailout when threshold is exceeded for performance

3. **Caching System** (`cache/` package)
   - `BiomeRegionCache`: Region-based cache (512x512 blocks) with LRU eviction
   - `HeightmapCache`: Sparse grid sampling (configurable spacing) with interpolation
   - Thread-safe with collaborative processing support

4. **Configuration** (`config/` package)
   - `BiomePrunerConfig`: ForgeConfigSpec-based configuration
   - `ConfigManager`: Static access to config values with caching
   - Config file: `config/biomepruner-common.toml`

### Processing Flow
1. Minecraft requests biome at position (x,y,z)
2. Mixin intercepts call and passes to BiomeSmoother
3. Position is projected to surface using heightmap cache
4. Flood fill determines biome size at surface
5. If size < threshold, find dominant neighbor
6. Result is cached and returned

### Thread Safety
- Multiple threads can collaborate on same flood fill task
- Cache operations are atomic with proper locking
- Designed for C2ME compatibility

### Performance Considerations
- Heightmap cache reduces expensive height calculations
- Region cache prevents redundant flood fills
- Early bailout in flood fill when threshold exceeded
- Configurable memory limits and cache sizes

## Development Tips

### Adding New Features
- New biome analysis should extend the core algorithm in `BiomeSmoother`
- Cache new computed data in appropriate cache classes
- Add config options to `BiomePrunerConfig` for user control

### Debugging
- Enable `debug` and `debugMessages` in config for detailed logging
- Use `/biomepruner check` command to analyze specific positions
- Performance tracker provides timing breakdown by section

### Mixin Development
- Keep mixin methods minimal - delegate to regular classes
- Use `@Unique` prefix for added methods to avoid conflicts
- Test with other mods that might also mixin to the same classes

## Configuration System
The mod uses NeoForge's configuration system with a TOML file. Key config categories:
- General settings (enabled, threshold, debug)
- Biome blacklists (preserved, excluded)
- Performance settings (cache sizes, grid spacing)
- Debug options (messages, performance logging)

## NeoForge Documentation

When working on this mod, refer to the official NeoForge javadocs for accurate API information:

**NeoForge Javadocs**: https://aldak.netlify.app/javadoc/1.21.1-21.1.x/

### How to Use the Javadocs

1. **Before making changes**: Check the javadocs to understand the correct API usage
2. **When fixing errors**: Look up classes/methods mentioned in error messages
3. **For context**: Understand inheritance hierarchies, method signatures, and deprecations

### Navigation Tips

- Use the **Packages** frame to browse by namespace
- Use the **Classes** frame to find specific classes
- Use the **Search** feature for quick lookups
- Check **class hierarchies** to understand inheritance
- Review **method details** for parameter types and return values

### Common Lookups for Error Resolution

When you encounter compilation errors, check the javadocs for:
- Missing imports → Find the correct package for a class
- Method not found → Verify method names and signatures
- Type mismatches → Check expected parameter/return types
- Deprecated APIs → Find recommended replacements

## Key Principles

1. **Always validate after changes** - Never assume your code changes are correct
2. **Read error messages carefully** - The build script provides detailed context
3. **Fix root causes** - Don't just comment out problematic code
4. **Iterate until green** - Keep fixing and validating until the build passes
5. **Reference the javadocs** - Use official documentation to ensure correct API usage

## Development Tools

### Minecraft Log Parsing
- **Script**: `minecraft_log_parser.py`
- **Purpose**: 
  - Find log entries marked as WARN, ERROR, or FATAL
  - Include continuation lines (like stack traces)
  - Save errors to `minecraft_log_errors.md`
- **Usage**: 
  - Only run when directed by the developer
  - Prevents processing potentially outdated logs