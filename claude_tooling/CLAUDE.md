# Claude Code Tooling

## Scripts

- **`claude_tooling/scripts/build_script.py`** - Gradle build automation with error reporting
- **`claude_tooling/scripts/run_automated_tests.py`** - Automated testing orchestration with integrated log parsing

## Usage

```bash
# Build validation
python claude_tooling/scripts/build_script.py

# Run automated tests (includes automatic log parsing)
python claude_tooling/scripts/run_automated_tests.py
```

## Build Script

- **Exit codes**: 0 = success, 1 = build failed, 2 = script error
- **Features**: Parses compilation errors with file locations and source context

### Output Files
- **`claude_tooling/build_output/build_errors.json`** - Structured error data with file paths, line numbers, and error messages
- **`claude_tooling/build_output/build_error_report.md`** - Human-readable report with code context and snippets

## Testing Script

- **Exit codes**: 0 = success, 1 = failed/timeout, 2 = config error, 3 = process error
- **Process**: Enables testing in config, runs `gradlew buildAndRun`, monitors results, restores config
- **Dependencies**: Auto-installs psutil, toml, watchdog if missing

### Output Structure
- **`claude_tooling/test_output/results/`**:
  - `test_results.json` - Latest test results with biome tests, performance metrics, and summary statistics
  - `test_results_YYYYMMDD_HHMMSS.json` - Timestamped backups for historical tracking
- **`claude_tooling/test_output/logs/`**:
  - `minecraft_latest.log`, `minecraft_debug.log` - Raw Minecraft logs from test run
  - `minecraft_latest_errors.md`, `minecraft_debug_errors.md` - Parsed WARN/ERROR/FATAL messages with timestamps and stack traces