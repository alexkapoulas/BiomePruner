#!/usr/bin/env python3
"""
BiomePruner Automated Testing Script

This script orchestrates automated testing by:
1. Temporarily enabling automated testing in the mod config
2. Launching Minecraft via Gradle buildAndRun task
3. Monitoring test progress and results
4. Collecting test artifacts and logs
5. Cleaning up and restoring original configuration

Usage: 
  python run_automated_tests.py [--tests TEST_TYPE]
  
Options:
  --tests TEST_TYPE   Specify which tests to run:
                      'all' (default) - Run both biome and performance tests
                      'biome' - Run only biome replacement verification tests
                      'performance' - Run only performance stress tests
                      'biome+performance' - Run biome tests first, then performance if biome tests pass
"""

import sys
import os
import time
import json
import shutil
import subprocess
import threading
import re
import argparse
from pathlib import Path
from datetime import datetime
from typing import Optional, List, Dict, Any


try:
    import psutil
    import toml
    from watchdog.observers import Observer
    from watchdog.events import FileSystemEventHandler
    HAS_DEPS = True
except ImportError:
    HAS_DEPS = False

# Configuration - adjust for new claude_tooling structure
PROJECT_ROOT = Path(__file__).parent.parent.parent
TEST_OUTPUT_DIR = Path(__file__).parent.parent / "test_output"
BUILD_OUTPUT_DIR = Path(__file__).parent.parent / "build_output"
INSTANCE_PATH = Path(r"C:\Users\Alex\AppData\Roaming\PrismLauncher\instances\BiomePruner\minecraft")
CONFIG_FILE = INSTANCE_PATH / "config" / "biomepruner-common.toml"
RESULTS_FILE = INSTANCE_PATH / "biomepruner_test_results.json"
LOG_FILES = {
    "latest": INSTANCE_PATH / "logs" / "latest.log",
    "debug": INSTANCE_PATH / "logs" / "debug.log"
}

# PrismLauncher configuration
PRISM_LAUNCHER_EXE = Path(r"C:\Users\Alex\AppData\Local\Programs\PrismLauncher\prismlauncher.exe")
PRISM_STARTUP_WAIT_SECONDS = 5

# Timeouts and limits
TEST_TIMEOUT_SECONDS = 600  # 10 minutes max
MAX_LOG_SIZE_MB = 50
GRADLE_TIMEOUT_SECONDS = 300  # 5 minutes for build
MAX_TEST_RESULT_FILES = 5  # Maximum number of timestamped test result files to keep


class MinecraftLogParser:
    """Parses Minecraft logs to extract errors and warnings."""
    
    def __init__(self, log_path: Path):
        self.log_path = log_path
        self.entries = []

    def parse_log(self) -> bool:
        """Parse the log file and extract all warnings and errors"""
        if not self.log_path.exists():
            return False

        try:
            with open(self.log_path, 'r', encoding='utf-8', errors='replace') as f:
                lines = f.readlines()
        except Exception:
            return False

        # Pattern to match log entries with WARN or ERROR levels
        log_pattern = re.compile(r'^\[([^\]]+)\]\s*\[([^\]]+)/(WARN|ERROR|FATAL)\]')
        current_entry = None

        for i, line in enumerate(lines):
            match = log_pattern.match(line)

            if match:
                # Save previous entry if exists
                if current_entry:
                    self.entries.append(current_entry)

                # Start new entry
                current_entry = {
                    'line_number': i + 1,
                    'timestamp': match.group(1),
                    'category': match.group(2),
                    'level': match.group(3),
                    'content': [line.rstrip()]
                }
            elif current_entry:
                # Continue collecting lines for current entry
                # Stop if we hit another timestamped line (even if not error/warn)
                if line.startswith('[') and '] [' in line:
                    self.entries.append(current_entry)
                    current_entry = None
                else:
                    current_entry['content'].append(line.rstrip())

        # Don't forget the last entry
        if current_entry:
            self.entries.append(current_entry)

        return True

    def generate_report(self, log_name: str) -> str:
        """Generate a markdown report with all warnings and errors"""
        if not self.entries:
            return f"# Minecraft Log Errors and Warnings ({log_name})\n\nNo warnings or errors found in the log file.\n"

        report = []
        report.append(f"# Minecraft Log Errors and Warnings ({log_name})\n")
        report.append(f"**Log File:** `{self.log_path}`\n")
        report.append(f"**Total Issues:** {len(self.entries)}\n")
        report.append("---\n")

        for entry in self.entries:
            report.append(f"## Line {entry['line_number']}: {entry['level']}")
            report.append(f"**Time:** {entry['timestamp']}")
            report.append(f"**Category:** {entry['category']}\n")
            report.append("```")
            report.extend(entry['content'])
            report.append("```\n")

        return '\n'.join(report)


class TestResultsWatcher(FileSystemEventHandler):
    """Watches for test results file changes."""
    
    def __init__(self):
        self.results_updated = threading.Event()
        self.last_modified = None
        
    def on_modified(self, event):
        if event.is_directory:
            return
            
        if Path(event.src_path).name == "biomepruner_test_results.json":
            current_time = time.time()
            if self.last_modified is None or (current_time - self.last_modified) > 2:
                self.last_modified = current_time
                self.results_updated.set()
                print(f"[INFO] Test results file updated: {event.src_path}")


class AutomatedTestRunner:
    """Main test runner class."""
    
    def __init__(self, test_mode='all'):
        self.gradle_process = None
        self.minecraft_processes = []
        self.observer = None
        self.watcher = None
        self.config_backup = None
        self.start_time = None
        self.test_mode = test_mode
        
    def check_dependencies(self) -> bool:
        """Check if required dependencies are installed."""
        if not HAS_DEPS:
            print("[ERROR] Missing required dependencies. Install with:")
            print("pip install psutil toml watchdog")
            return False
        return True
        
    def check_paths(self) -> bool:
        """Verify all required paths exist."""
        if not INSTANCE_PATH.exists():
            print(f"[ERROR] Minecraft instance not found: {INSTANCE_PATH}")
            return False
            
        if not CONFIG_FILE.exists():
            print(f"[ERROR] Config file not found: {CONFIG_FILE}")
            return False
            
        # Always check for Unix gradle wrapper in Claude Code environment
        gradlew = PROJECT_ROOT / "gradlew"
            
        if not gradlew.exists():
            print(f"[ERROR] Gradle wrapper not found: {gradlew}")
            return False
            
        return True
        
    def is_prism_launcher_running(self) -> bool:
        """Check if PrismLauncher is currently running."""
        for process in psutil.process_iter(['pid', 'name']):
            try:
                if 'prismlauncher' in process.info['name'].lower():
                    return True
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue
        return False
        
    def is_prism_launcher_responsive(self) -> bool:
        """Check if PrismLauncher is running and responsive."""
        for process in psutil.process_iter(['pid', 'name']):
            try:
                if 'prismlauncher' in process.info['name'].lower():
                    # Try to get process status to check if it's responsive
                    status = process.status()
                    # If we can get the status, it's likely responsive
                    return status in [psutil.STATUS_RUNNING, psutil.STATUS_SLEEPING]
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue
        return False
        
    def start_prism_launcher(self) -> bool:
        """Start PrismLauncher if it's not already running."""
        if self.is_prism_launcher_running():
            if self.is_prism_launcher_responsive():
                print("[INFO] PrismLauncher is already running and responsive")
                return True
            else:
                print("[INFO] PrismLauncher is running but may be unresponsive, leaving it as-is")
                return True
                
        if not PRISM_LAUNCHER_EXE.exists():
            print(f"[ERROR] PrismLauncher executable not found: {PRISM_LAUNCHER_EXE}")
            return False
            
        try:
            print("[INFO] Starting PrismLauncher...")
            subprocess.Popen(
                [str(PRISM_LAUNCHER_EXE)],
                creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if os.name == 'nt' else 0
            )
            
            # Wait for PrismLauncher to start up
            print(f"[INFO] Waiting {PRISM_STARTUP_WAIT_SECONDS} seconds for PrismLauncher to start...")
            time.sleep(PRISM_STARTUP_WAIT_SECONDS)
            
            # Verify it started
            if self.is_prism_launcher_running():
                print("[INFO] PrismLauncher started successfully")
                return True
            else:
                print("[ERROR] PrismLauncher failed to start within timeout")
                return False
                
        except Exception as e:
            print(f"[ERROR] Failed to start PrismLauncher: {e}")
            return False
        
    def backup_config(self) -> bool:
        """Backup the current configuration."""
        try:
            with open(CONFIG_FILE, 'r') as f:
                self.config_backup = f.read()
            print(f"[INFO] Configuration backed up")
            return True
        except Exception as e:
            print(f"[ERROR] Failed to backup config: {e}")
            return False
            
    def enable_automated_testing(self) -> bool:
        """Enable automated testing with selective test configuration."""
        try:
            with open(CONFIG_FILE, 'r') as f:
                config = toml.load(f)
                
            # Ensure sections exist
            if 'general' not in config:
                config['general'] = {}
            if 'testing' not in config:
                config['testing'] = {}
                
            # Enable both the mod itself and automated testing
            config['testing']['enabled'] = True
            config['testing']['automatedTestingEnabled'] = True
            
            # Enable debug settings for all tests
            config['testing']['debug'] = True
            config['testing']['debugMessages'] = True
            print("[INFO] Enabled debug and debug messages for testing")
            
            # Configure which tests to run based on test_mode
            if self.test_mode == 'biome':
                config['testing']['biomeTestsEnabled'] = True
                config['testing']['performanceTestsEnabled'] = False
                config['testing']['performanceLogging'] = False
                print("[INFO] Configured for biome tests only")
            elif self.test_mode == 'performance':
                config['testing']['biomeTestsEnabled'] = False
                config['testing']['performanceTestsEnabled'] = True
                config['testing']['performanceLogging'] = True
                print("[INFO] Configured for performance tests only with performance logging")
            elif self.test_mode == 'biome+performance':
                config['testing']['biomeTestsEnabled'] = True
                config['testing']['performanceTestsEnabled'] = True
                config['testing']['performanceLogging'] = True
                print("[INFO] Configured for biome tests followed by performance tests with performance logging")
            else:  # 'all' or default
                config['testing']['biomeTestsEnabled'] = True
                config['testing']['performanceTestsEnabled'] = True
                config['testing']['performanceLogging'] = True
                print("[INFO] Configured for all tests with performance logging")
            
            with open(CONFIG_FILE, 'w') as f:
                toml.dump(config, f)
                
            print("[INFO] Mod and automated testing enabled in configuration")
            return True
        except Exception as e:
            print(f"[ERROR] Failed to enable automated testing: {e}")
            return False
            
    def restore_config(self) -> bool:
        """Restore the original configuration."""
        if self.config_backup is None:
            return True
            
        try:
            with open(CONFIG_FILE, 'w') as f:
                f.write(self.config_backup)
            print("[INFO] Configuration restored")
            return True
        except Exception as e:
            print(f"[ERROR] Failed to restore config: {e}")
            return False
            
    def setup_file_watcher(self) -> bool:
        """Set up file system watcher for test results."""
        try:
            self.watcher = TestResultsWatcher()
            self.observer = Observer()
            self.observer.schedule(self.watcher, str(INSTANCE_PATH), recursive=False)
            self.observer.start()
            print("[INFO] File watcher started")
            return True
        except Exception as e:
            print(f"[ERROR] Failed to setup file watcher: {e}")
            return False
            
    def cleanup_old_results(self):
        """Remove old test results and logs."""
        # Create output directories if they don't exist
        TEST_OUTPUT_DIR.mkdir(exist_ok=True)
        
        files_to_clean = [
            RESULTS_FILE,
            TEST_OUTPUT_DIR / "test_results.json",
            TEST_OUTPUT_DIR / "minecraft_latest.log",
            TEST_OUTPUT_DIR / "minecraft_debug.log"
        ]
        
        for file_path in files_to_clean:
            if file_path.exists():
                try:
                    file_path.unlink()
                    print(f"[INFO] Cleaned old file: {file_path.name}")
                except Exception as e:
                    print(f"[WARN] Could not clean {file_path}: {e}")
                    
    def cleanup_old_test_result_files(self):
        """Keep only the most recent test result files, delete older ones."""
        results_dir = TEST_OUTPUT_DIR / "results"
        if not results_dir.exists():
            return
            
        # Find all timestamped test result files
        pattern = "test_results_*.json"
        timestamped_files = list(results_dir.glob(pattern))
        
        if len(timestamped_files) <= MAX_TEST_RESULT_FILES:
            return  # No cleanup needed
            
        # Sort by modification time (newest first)
        timestamped_files.sort(key=lambda f: f.stat().st_mtime, reverse=True)
        
        # Keep only the most recent MAX_TEST_RESULT_FILES
        files_to_keep = timestamped_files[:MAX_TEST_RESULT_FILES]
        files_to_delete = timestamped_files[MAX_TEST_RESULT_FILES:]
        
        for file_path in files_to_delete:
            try:
                file_path.unlink()
                print(f"[INFO] Deleted old test result file: {file_path.name}")
            except Exception as e:
                print(f"[WARN] Could not delete old test result file {file_path}: {e}")
                
        if files_to_delete:
            print(f"[INFO] Kept {len(files_to_keep)} most recent test result files, deleted {len(files_to_delete)} old files")
                    
    def launch_gradle_build(self) -> bool:
        """Launch the Gradle buildAndRun task."""
        try:
            # Use the exact same approach as the build script for compatibility
            gradle_wrapper = PROJECT_ROOT / "gradlew.bat"
            gradle_args = ["buildAndRun", "--no-daemon", "--console=plain"]
            
            # Method 1: Direct execution with .bat file (from build script)
            try:
                cmd = [str(gradle_wrapper)] + gradle_args

                # Use subprocess.run first to test, then convert to Popen if successful
                test_result = subprocess.run(
                    cmd,
                    cwd=str(PROJECT_ROOT),
                    capture_output=True,
                    text=True,
                    timeout=10,  # Quick test
                    shell=False,
                    env={**os.environ, "TERM": "dumb"}
                )

                if test_result.returncode != 0 and "/c:" in test_result.stderr:
                    raise Exception("Shell interpretation error detected")

                self.gradle_process = subprocess.Popen(
                    cmd,
                    cwd=str(PROJECT_ROOT),
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    shell=False,
                    env={**os.environ, "TERM": "dumb"}
                )

            except Exception as e:
                # Method 2: Use cmd.exe explicitly (from build script)
                gradle_cmd = f'{gradle_wrapper} {" ".join(gradle_args)}'
                cmd = ["cmd.exe", "/c", gradle_cmd]
                
                self.gradle_process = subprocess.Popen(
                    cmd,
                    cwd=str(PROJECT_ROOT),
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    shell=False,
                    env={**os.environ, "TERM": "dumb"}
                )
            
            # Start a thread to monitor Gradle output
            threading.Thread(
                target=self._monitor_gradle_output,
                daemon=True
            ).start()
            
            return True
        except Exception as e:
            print(f"[ERROR] Failed to launch Gradle: {e}")
            return False
            
    def _monitor_gradle_output(self):
        """Monitor Gradle output for important messages."""
        if not self.gradle_process:
            return
            
        try:
            import queue
            import threading
            import time
            
            # Use a queue-based approach to avoid blocking I/O
            output_queue = queue.Queue()
            
            def enqueue_output():
                """Thread function to read output and put it in queue."""
                try:
                    while True:
                        line = self.gradle_process.stdout.readline()
                        if not line:
                            break
                        output_queue.put(line.strip())
                except Exception:
                    pass
                finally:
                    output_queue.put(None)  # Signal end
            
            # Start the output reading thread
            reader_thread = threading.Thread(target=enqueue_output, daemon=True)
            reader_thread.start()
            
            # Main monitoring loop with timeouts
            while self.gradle_process.poll() is None:
                try:
                    # Try to get output with timeout
                    line = output_queue.get(timeout=1.0)
                    
                    if line is None:  # End signal
                        break
                        
                    if line:
                        # Show important Gradle messages
                        if any(keyword in line.lower() for keyword in 
                              ['error', 'failed', 'exception', 'build successful', 'launching']):
                            print(f"[GRADLE] {line}")
                            
                except queue.Empty:
                    # No output received within timeout, continue monitoring
                    continue
                except Exception:
                    break
            
            # Process any remaining output
            try:
                while True:
                    line = output_queue.get_nowait()
                    if line and line is not None:
                        if any(keyword in line.lower() for keyword in 
                              ['error', 'failed', 'exception', 'build successful', 'launching']):
                            print(f"[GRADLE] {line}")
            except queue.Empty:
                pass
                        
        except Exception as e:
            print(f"[WARN] Error monitoring Gradle output: {e}")
            
    def wait_for_minecraft_start(self) -> bool:
        """Wait for Minecraft to start and begin testing."""
        print("[INFO] Waiting for Minecraft to start...")
        
        start_time = time.time()
        while time.time() - start_time < TEST_TIMEOUT_SECONDS:
            # Look for Minecraft processes
            minecraft_procs = []
            for process in psutil.process_iter(['pid', 'name', 'cmdline']):
                try:
                    if 'java' in process.info['name'].lower():
                        cmdline = ' '.join(process.info['cmdline'] or [])
                        if 'minecraft' in cmdline.lower() or 'biomepruner' in cmdline.lower():
                            minecraft_procs.append(process)
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    continue
                    
            if minecraft_procs:
                self.minecraft_processes = minecraft_procs
                print(f"[INFO] Found {len(minecraft_procs)} Minecraft process(es)")
                return True
                
            time.sleep(2)
            
        print("[ERROR] Minecraft did not start within timeout")
        return False
        
    def monitor_log_sizes(self) -> bool:
        """Check if log files are growing too large."""
        for name, log_file in LOG_FILES.items():
            if log_file.exists():
                size_mb = log_file.stat().st_size / (1024 * 1024)
                if size_mb > MAX_LOG_SIZE_MB:
                    print(f"[WARN] {name} log file too large: {size_mb:.1f}MB")
                    return False
        return True
        
    def wait_for_test_completion(self) -> bool:
        """Wait for tests to complete or timeout."""
        print("[INFO] Waiting for test completion...")
        
        # Wait for test results file to be updated
        completed = self.watcher.results_updated.wait(timeout=TEST_TIMEOUT_SECONDS)
        
        if completed:
            print("[INFO] Test results detected!")
            # Give a moment for file to be fully written
            time.sleep(2)
            return True
        else:
            print("[ERROR] Test timeout - no results file detected")
            return False
            
    def shutdown_minecraft(self):
        """Gracefully shutdown Minecraft processes, keep PrismLauncher running if responsive."""
        print("[INFO] Shutting down Minecraft...")
        
        # First, try to find and terminate Minecraft processes
        for process in psutil.process_iter(['pid', 'name', 'cmdline']):
            try:
                cmdline = ' '.join(process.info['cmdline'] or [])
                if ('java' in process.info['name'].lower() and 
                    ('minecraft' in cmdline.lower() or 'biomepruner' in cmdline.lower())):
                    
                    print(f"[INFO] Terminating Minecraft process {process.pid}")
                    process.terminate()
                    
                    # Wait for graceful shutdown
                    try:
                        process.wait(timeout=10)
                        print(f"[INFO] Process {process.pid} terminated gracefully")
                    except psutil.TimeoutExpired:
                        print(f"[WARN] Force killing process {process.pid}")
                        process.kill()
                        
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue
                
        # Check PrismLauncher status - only terminate if unresponsive
        if self.is_prism_launcher_running():
            if self.is_prism_launcher_responsive():
                print("[INFO] Keeping PrismLauncher running for faster future test execution")
            else:
                print("[INFO] PrismLauncher appears unresponsive, terminating it")
                for process in psutil.process_iter(['pid', 'name']):
                    try:
                        if 'prismlauncher' in process.info['name'].lower():
                            print(f"[INFO] Terminating unresponsive PrismLauncher {process.pid}")
                            process.terminate()
                            try:
                                process.wait(timeout=5)
                                print(f"[INFO] PrismLauncher {process.pid} terminated")
                            except psutil.TimeoutExpired:
                                print(f"[WARN] Force killing PrismLauncher {process.pid}")
                                process.kill()
                    except (psutil.NoSuchProcess, psutil.AccessDenied):
                        continue
                
    def collect_test_artifacts(self) -> bool:
        """Collect test results and log files."""
        print("[INFO] Collecting test artifacts...")
        
        # Ensure output directory exists
        TEST_OUTPUT_DIR.mkdir(exist_ok=True)
        
        artifacts_collected = 0
        
        # Create results subdirectory
        results_dir = TEST_OUTPUT_DIR / "results"
        results_dir.mkdir(exist_ok=True)
        
        # Copy test results
        if RESULTS_FILE.exists():
            try:
                # Copy main results file to results subdirectory
                dest = results_dir / "test_results.json"
                shutil.copy2(RESULTS_FILE, dest)
                print(f"[INFO] Copied test results to {dest}")
                artifacts_collected += 1
                
                # Also create timestamped backup in results subdirectory
                timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
                backup_dest = results_dir / f"test_results_{timestamp}.json"
                shutil.copy2(RESULTS_FILE, backup_dest)
                print(f"[INFO] Created timestamped backup: {backup_dest}")
                
                # Clean up old timestamped files
                self.cleanup_old_test_result_files()
                
            except Exception as e:
                print(f"[ERROR] Failed to copy test results: {e}")
        else:
            print("[WARN] No test results file found")
            
        # Create logs subdirectory
        logs_dir = TEST_OUTPUT_DIR / "logs"
        logs_dir.mkdir(exist_ok=True)
        
        # Copy and parse log files
        for name, log_file in LOG_FILES.items():
            if log_file.exists():
                try:
                    # Copy log file to logs subdirectory
                    dest = logs_dir / f"minecraft_{name}.log"
                    shutil.copy2(log_file, dest)
                    print(f"[INFO] Copied {name} log to {dest}")
                    artifacts_collected += 1
                    
                    # Parse log file for errors and warnings
                    parser = MinecraftLogParser(log_file)
                    if parser.parse_log():
                        report = parser.generate_report(name)
                        report_dest = logs_dir / f"minecraft_{name}_errors.md"
                        
                        try:
                            with open(report_dest, 'w', encoding='utf-8') as f:
                                f.write(report)
                            print(f"[INFO] Generated error report: {report_dest} ({len(parser.entries)} issues)")
                            artifacts_collected += 1
                        except Exception as e:
                            print(f"[ERROR] Failed to save {name} error report: {e}")
                    else:
                        print(f"[WARN] Could not parse {name} log file")
                        
                except Exception as e:
                    print(f"[ERROR] Failed to copy {name} log: {e}")
            else:
                print(f"[WARN] No {name} log file found")
                
        return artifacts_collected > 0
        
    def analyze_test_results(self) -> Dict[str, Any]:
        """Analyze collected test results."""
        results_file = TEST_OUTPUT_DIR / "results" / "test_results.json"
        
        if not results_file.exists():
            return {"success": False, "error": "No test results file found"}
            
        try:
            with open(results_file, 'r') as f:
                results = json.load(f)
                
            summary = results.get('summary', {})
            
            analysis = {
                "success": True,
                "total_biome_tests": summary.get('totalBiomeTests', 0),
                "passed_biome_tests": summary.get('passedBiomeTests', 0),
                "failed_biome_tests": summary.get('failedBiomeTests', 0),
                "performance_test_completed": summary.get('performanceTestCompleted', False)
            }
            
            # Calculate success rate
            if analysis['total_biome_tests'] > 0:
                analysis['success_rate'] = analysis['passed_biome_tests'] / analysis['total_biome_tests']
            else:
                analysis['success_rate'] = 1.0 if self.test_mode in ['performance'] else 0.0
                
            return analysis
            
        except Exception as e:
            return {"success": False, "error": f"Failed to analyze results: {e}"}
            
    def cleanup(self):
        """Clean up resources and processes."""
        print("[INFO] Cleaning up...")
        
        # Stop file observer
        if self.observer:
            try:
                self.observer.stop()
                self.observer.join(timeout=5)
            except Exception:
                pass
                
        # Terminate Gradle process
        if self.gradle_process:
            try:
                self.gradle_process.terminate()
                self.gradle_process.wait(timeout=10)
            except Exception:
                try:
                    self.gradle_process.kill()
                except Exception:
                    pass
                    
        # Restore configuration
        self.restore_config()
        
    def run(self) -> int:
        """Main test execution method."""
        print(f"=== BiomePruner Automated Testing ({self.test_mode}) ===")
        self.start_time = time.time()
        
        try:
            # Pre-flight checks
            if not self.check_dependencies():
                return 2
                
            if not self.check_paths():
                return 2
                
            # Setup
            if not self.backup_config():
                return 2
                
            if not self.enable_automated_testing():
                return 2
                
            # Start PrismLauncher if needed
            if not self.start_prism_launcher():
                return 2
                
            if not self.setup_file_watcher():
                return 2
                
            self.cleanup_old_results()
            
            # Launch testing
            if not self.launch_gradle_build():
                return 3
                
            if not self.wait_for_minecraft_start():
                return 3
                
            # Monitor testing
            if not self.wait_for_test_completion():
                return 1
                
            # Shutdown and collect results
            self.shutdown_minecraft()
            
            if not self.collect_test_artifacts():
                return 1
                
            # Analyze results
            analysis = self.analyze_test_results()
            
            # Report results
            elapsed = time.time() - self.start_time
            print(f"\n=== Test Results ===")
            print(f"Execution time: {elapsed:.1f} seconds")
            
            if analysis['success']:
                # Report based on what tests were configured to run
                if self.test_mode in ['all', 'biome', 'biome+performance']:
                    print(f"Biome tests: {analysis['passed_biome_tests']}/{analysis['total_biome_tests']} passed")
                    print(f"Success rate: {analysis['success_rate']:.1%}")
                
                if self.test_mode in ['all', 'performance', 'biome+performance']:
                    print(f"Performance test: {'PASS' if analysis['performance_test_completed'] else 'FAIL'}")
                
                # Determine overall success based on configured tests
                biome_success = True
                performance_success = True
                
                if self.test_mode in ['all', 'biome', 'biome+performance']:
                    biome_success = analysis['success_rate'] == 1.0
                
                if self.test_mode in ['all', 'performance', 'biome+performance']:
                    performance_success = analysis['performance_test_completed']
                    
                if biome_success and performance_success:
                    print("[SUCCESS] All configured tests passed!")
                    return 0
                else:
                    print("[PARTIAL] Some configured tests failed")
                    return 1
            else:
                print(f"[ERROR] {analysis['error']}")
                return 1
                
        except KeyboardInterrupt:
            print("[INFO] Test interrupted by user")
            return 1
        except Exception as e:
            print(f"[ERROR] Unexpected error: {e}")
            return 3
        finally:
            self.cleanup()


def parse_arguments():
    """Parse command line arguments."""
    parser = argparse.ArgumentParser(
        description='BiomePruner Automated Testing Script',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Test Types:
  all              Run both biome and performance tests (default)
  biome            Run only biome replacement verification tests
  performance      Run only performance stress tests
  biome+performance Run biome tests first, then performance if biome tests pass

Examples:
  python run_automated_tests.py                    # Run all tests
  python run_automated_tests.py --tests biome      # Run only biome tests
  python run_automated_tests.py --tests performance # Run only performance tests
        """)
    
    parser.add_argument(
        '--tests', 
        choices=['all', 'biome', 'performance', 'biome+performance'],
        default='all',
        help='Specify which tests to run (default: all)'
    )
    
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_arguments()
    runner = AutomatedTestRunner(test_mode=args.tests)
    exit_code = runner.run()
    sys.exit(exit_code)