# Claude Code Tooling

## Scripts

- **`claude_tooling/scripts/build_script.py`** - Gradle build automation with error reporting
- **`claude_tooling/scripts/run_automated_tests.py`** - Automated testing orchestration with integrated log parsing
- **`claude_tooling/scripts/config_manager.py`** - Safe mod configuration management (excludes testing section)
- **`claude_tooling/scripts/log_parser.py`** - Standalone log parser for warnings, errors, and BiomePruner messages

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

# Configuration management (read/write mod settings, excludes testing section)
python claude_tooling/scripts/config_manager.py read general microBiomeThreshold
python claude_tooling/scripts/config_manager.py write general microBiomeThreshold 75
python claude_tooling/scripts/config_manager.py sections

# Log parsing (standalone analysis of Minecraft logs)
python claude_tooling/scripts/log_parser.py                    # Parse both latest and debug logs
python claude_tooling/scripts/log_parser.py --log-type latest  # Parse only latest.log
python claude_tooling/scripts/log_parser.py --log-type debug   # Parse only debug.log
```

## Build Script

- **Exit codes**: 0 = success, 1 = build failed, 2 = script error
- **Features**: Parses compilation errors with file locations and source context

### Output Files
- **`claude_tooling/build_output/build_errors.json`** - Structured error data with file paths, line numbers, and error messages
- **`claude_tooling/build_output/build_error_report.md`** - Human-readable report with code context and snippets

## Testing Script

- **Exit codes**: 0 = success, 1 = failed/timeout, 2 = config error, 3 = process error
- **Process**: Enables testing in config, starts PrismLauncher if needed, runs `gradlew buildAndRun`, monitors results, restores config
- **Dependencies**: Auto-installs psutil, toml, watchdog if missing
- **Test Selection**: Supports selective test execution via `--tests` parameter for biome tests only, performance tests only, or both
- **Configuration**: Uses separate config flags (`biomeTestsEnabled`, `performanceTestsEnabled`) to control which tests run
- **Debug Settings**: Automatically enables `debug=true` and `debugMessages=true` for all test runs
- **Performance Logging**: Automatically enables `performanceLogging=true` when running performance tests
- **PrismLauncher Management**: Automatically starts PrismLauncher if not running, keeps it running after tests complete for faster subsequent executions

### Test Selection Guidelines

- **`--tests biome+performance`** (PREFERRED DEFAULT): Run biome tests first, then performance tests only if biome tests pass. This saves time since biome replacement issues invalidate performance gains. Enables debug and performance logging. Keeps PrismLauncher running for subsequent tests.
- **`--tests biome`** (RECOMMENDED FOR CLAUDE): Quick functionality testing when validating mod changes. Fastest option for verifying biome replacement accuracy. Enables debug settings but not performance logging for efficiency. Keeps PrismLauncher running for subsequent tests.
- **`--tests performance`**: Use only when biome functionality is already validated and you need performance metrics. Enables debug and performance logging for detailed analysis. Keeps PrismLauncher running for subsequent tests.
- **`--tests all`**: Full test suite that runs both test types regardless of biome test results. Use sparingly as it's less efficient. Enables debug and performance logging. Keeps PrismLauncher running for subsequent tests.

### Output Structure
- **`claude_tooling/test_output/results/`**:
  - `test_results.json` - Latest test results with biome tests, performance metrics, and summary statistics
  - `test_results_YYYYMMDD_HHMMSS.json` - Timestamped backups for historical tracking (keeps most recent 5 files, deletes older ones automatically)
- **`claude_tooling/test_output/logs/`**:
  - `minecraft_latest.log`, `minecraft_debug.log` - Raw Minecraft logs from test run
  - `minecraft_latest_errors.md`, `minecraft_debug_errors.md` - Parsed WARN/ERROR/FATAL messages with timestamps and stack traces
- **`claude_tooling/config_backups/`**:
  - `biomepruner-config-backup_YYYYMMDD_HHMMSS.toml` - Timestamped configuration backups

## Configuration Manager

- **Sections**: `general`, `biome_blacklist`, `performance`, `heightmap` (testing section is protected)
- **Commands**: `read`, `write`, `list`, `sections`, `backup`, `restore`
- **Auto-backup**: Creates backup before restoration operations

## Log Parser

- **Exit codes**: 0 = success, 1 = no logs parsed, 2 = partial success
- **Process**: Scans Minecraft log files for warnings, errors, fatal errors, and BiomePruner-specific messages
- **Features**: Creates separate markdown reports for errors/warnings and BiomePruner messages, plus JSON data export

### Output Files
- **`claude_tooling/log_output/latest_errors_warnings.md`** - Formatted report of all warnings, errors, and fatal errors from latest.log
- **`claude_tooling/log_output/latest_biomepruner_messages.md`** - All BiomePruner-related messages from latest.log
- **`claude_tooling/log_output/latest_parsed_data.json`** - Structured JSON data for programmatic analysis
- **`claude_tooling/log_output/debug_*`** - Same file types for debug.log when parsed