# Claude Code Tooling

## Scripts

- **`claude_tooling/scripts/build_script.py`** - Gradle build automation with error reporting
- **`claude_tooling/scripts/run_automated_tests.py`** - Automated testing orchestration with integrated log parsing

## Usage

```bash
# Build validation
python claude_tooling/scripts/build_script.py

# Run automated tests (includes automatic log parsing)
# PREFERRED DEFAULT: Run biome tests first, then performance if biome tests pass
python claude_tooling/scripts/run_automated_tests.py --tests biome+performance

# Quick mod functionality testing (recommended for Claude when testing changes)
python claude_tooling/scripts/run_automated_tests.py --tests biome

# Performance-only testing (when biome functionality is already validated)
python claude_tooling/scripts/run_automated_tests.py --tests performance

# Full test suite (runs both tests regardless of biome test results)
python claude_tooling/scripts/run_automated_tests.py --tests all
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
- **Test Selection**: Supports selective test execution via `--tests` parameter for biome tests only, performance tests only, or both
- **Configuration**: Uses separate config flags (`biomeTestsEnabled`, `performanceTestsEnabled`) to control which tests run
- **Debug Settings**: Automatically enables `debug=true` and `debugMessages=true` for all test runs
- **Performance Logging**: Automatically enables `performanceLogging=true` when running performance tests

### Test Selection Guidelines

- **`--tests biome+performance`** (PREFERRED DEFAULT): Run biome tests first, then performance tests only if biome tests pass. This saves time since biome replacement issues invalidate performance gains. Enables debug and performance logging.
- **`--tests biome`** (RECOMMENDED FOR CLAUDE): Quick functionality testing when validating mod changes. Fastest option for verifying biome replacement accuracy. Enables debug settings but not performance logging for efficiency.
- **`--tests performance`**: Use only when biome functionality is already validated and you need performance metrics. Enables debug and performance logging for detailed analysis.
- **`--tests all`**: Full test suite that runs both test types regardless of biome test results. Use sparingly as it's less efficient. Enables debug and performance logging.

### Output Structure
- **`claude_tooling/test_output/results/`**:
  - `test_results.json` - Latest test results with biome tests, performance metrics, and summary statistics
  - `test_results_YYYYMMDD_HHMMSS.json` - Timestamped backups for historical tracking
- **`claude_tooling/test_output/logs/`**:
  - `minecraft_latest.log`, `minecraft_debug.log` - Raw Minecraft logs from test run
  - `minecraft_latest_errors.md`, `minecraft_debug_errors.md` - Parsed WARN/ERROR/FATAL messages with timestamps and stack traces