# Claude Code Development Guide

## Project Information

- **Minecraft Version**: 1.21.1
- **NeoForge Version**: 21.1.193
- **Parchment Mappings**: 2024.11.17 (MC 1.21.1)

## Development Workflow

### 1. After Making Source Code Changes
**Always validate your changes with the build script:**
```bash
python claude_tooling/scripts/build_script.py
```
- Exit code 0 = success, continue with your task
- Exit code 1 = build failed, check `claude_tooling/build_output/` for error reports
- Fix errors and repeat until build succeeds

### 2. Testing and Debugging
**When you need to test mod functionality or debug issues:**
```bash
python claude_tooling/scripts/run_automated_tests.py
```
- Runs comprehensive automated tests including biome replacement and performance tests
- Generates detailed reports in `claude_tooling/test_output/`
- Includes automatic log parsing and error extraction

### 3. Configuration Management
**When you need to modify mod settings:**
```bash
python claude_tooling/scripts/config_manager.py read general microBiomeThreshold
python claude_tooling/scripts/config_manager.py write general microBiomeThreshold 75
```
- Safe access to mod configuration (excludes protected testing section)
- Automatic type detection and validation
- Creates backups before major changes

### 4. Log Analysis
**When you need to analyze Minecraft logs for debugging:**
```bash
python claude_tooling/scripts/log_parser.py
```
- Extracts warnings, errors, and fatal errors from Minecraft logs
- Identifies BiomePruner-specific messages for debugging
- Outputs organized markdown reports and JSON data to `claude_tooling/log_output/`

## Project Directory Structure

```
BiomePruner/
├── CLAUDE.md                    # This development guide
├── claude_tooling/              # Claude Code automation scripts
│   ├── CLAUDE.md               # Tooling documentation (auto-loaded in tooling context)
│   ├── scripts/                # Python automation scripts
│   ├── build_output/           # Build validation reports
│   ├── test_output/            # Test results and logs
│   ├── log_output/             # Parsed log analysis reports
│   └── config_backups/         # Configuration backups
├── src/                        # Source code
│   ├── CLAUDE.md               # Source code documentation (auto-loaded in src context)
│   └── main/java/...           # Minecraft mod source files
├── build.gradle                # Gradle build configuration
└── gradlew                     # Gradle wrapper (use via Python scripts)
```

## Reference Documentation

### NeoForge API Documentation
When working with Minecraft mod APIs, refer to:
**NeoForge Javadocs**: https://aldak.netlify.app/javadoc/1.21.1-21.1.x/

Use the javadocs to:
- Verify method names and signatures when fixing compilation errors
- Find correct imports for classes
- Check parameter types and return values
- Identify deprecated APIs and their replacements

## Key Principles

1. **Always validate after changes** - Use the build script after any source modifications
2. **Test functionality changes** - Use the automated testing script for validation
3. **Check API documentation** - Reference NeoForge javadocs for correct usage
4. **Never run gradle directly** - Always use the Python automation scripts
5. **Keep documentation current** - When modifying files in `src/` or its subdirectories, update `src/CLAUDE.md` to reflect structural changes