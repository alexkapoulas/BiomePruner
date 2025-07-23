#!/usr/bin/env python3
"""
Minecraft Mod Build Script for Claude Code
Executes Gradle build and provides intelligent error feedback
"""

import subprocess
import sys
import re
import os
import json
from pathlib import Path
from typing import Dict, List, Tuple, Optional

# Output directory configuration - adjust for new claude_tooling structure
BUILD_OUTPUT_DIR = Path(__file__).parent.parent / "build_output"
BUILD_OUTPUT_DIR.mkdir(exist_ok=True)

class MinecraftModBuilder:
    def __init__(self, project_root: str = None):
        # Default to the project root (two levels up from this script)
        if project_root is None:
            project_root = Path(__file__).parent.parent.parent
        self.project_root = Path(project_root).resolve()
        if not self.project_root.exists():
            raise ValueError(f"Project root does not exist: {self.project_root}")
        self.gradle_wrapper = self.find_gradle_wrapper()

    def find_gradle_wrapper(self) -> str:
        """Find the appropriate gradle wrapper command"""
        if sys.platform.startswith('win'):
            wrapper = self.project_root / "gradlew.bat"
            if wrapper.exists():
                # Return absolute path to avoid shell interpretation issues
                return str(wrapper.absolute())
            return "gradle.bat"
        else:
            wrapper = self.project_root / "gradlew"
            if wrapper.exists():
                # Return absolute path to avoid shell interpretation issues
                return str(wrapper.absolute())
            return "gradle"

    def run_build(self) -> Tuple[bool, str, str]:
        """
        Execute the Gradle build command
        Returns: (success, stdout, stderr)
        """
        print("Starting Minecraft mod build...")
        print(f"Project root: {self.project_root}")
        print(f"Gradle wrapper: {self.gradle_wrapper}")

        # Make gradlew executable on Unix-like systems before running
        if not sys.platform.startswith('win') and self.gradle_wrapper.endswith('gradlew'):
            gradlew_path = Path(self.gradle_wrapper)
            if gradlew_path.exists():
                try:
                    os.chmod(str(gradlew_path), 0o755)
                except Exception as e:
                    print(f"Warning: Could not make gradle wrapper executable: {e}")

        # Build command
        gradle_args = ["build", "--no-daemon", "--console=plain"]

        try:
            # On Windows, try different execution methods
            if sys.platform.startswith('win'):
                # Method 1: Direct execution with .bat file
                print("Attempting direct Windows execution...")
                try:
                    # Windows needs special handling for .bat files
                    cmd = [self.gradle_wrapper] + gradle_args
                    print(f"Executing command: {' '.join(cmd)}")

                    result = subprocess.run(
                        cmd,
                        cwd=str(self.project_root),
                        capture_output=True,
                        text=True,
                        timeout=300,
                        shell=False,
                        env={**os.environ, "TERM": "dumb"}
                    )

                    if result.returncode != 0 and "/c:" in result.stderr:
                        raise Exception("Shell interpretation error detected")

                    return result.returncode == 0, result.stdout, result.stderr

                except Exception as e:
                    print(f"Direct execution failed: {e}")

                    # Method 2: Use cmd.exe explicitly
                    print("Trying with cmd.exe...")
                    cmd_line = f'{self.gradle_wrapper} {" ".join(gradle_args)}'
                    cmd = ["cmd.exe", "/c", cmd_line]
                    print(f"Executing: {' '.join(cmd)}")

                    result = subprocess.run(
                        cmd,
                        cwd=str(self.project_root),
                        capture_output=True,
                        text=True,
                        timeout=300,
                        shell=False,
                        env={**os.environ, "TERM": "dumb"}
                    )

                    return result.returncode == 0, result.stdout, result.stderr
            else:
                # Unix-like systems
                cmd = [self.gradle_wrapper] + gradle_args
                print(f"Executing command: {' '.join(cmd)}")

                result = subprocess.run(
                    cmd,
                    cwd=str(self.project_root),
                    capture_output=True,
                    text=True,
                    timeout=300,
                    shell=False,
                    env={**os.environ, "TERM": "dumb"}
                )

                return result.returncode == 0, result.stdout, result.stderr

        except subprocess.TimeoutExpired:
            return False, "", "Build timed out after 5 minutes"
        except Exception as e:
            # Final fallback: Try direct Python execution of gradlew
            print(f"Standard execution failed: {e}")
            print("Attempting alternative execution method...")

            try:
                # Read gradlew.bat and extract the actual java command
                if sys.platform.startswith('win') and self.gradle_wrapper.endswith('.bat'):
                    # For Windows, we'll use a simple shell command
                    import tempfile

                    # Create a temporary batch file to avoid shell interpretation issues
                    with tempfile.NamedTemporaryFile(mode='w', suffix='.bat', delete=False) as f:
                        f.write(f'@echo off\n')
                        f.write(f'cd /d "{self.project_root}"\n')
                        f.write(f'call "{self.gradle_wrapper}" build --no-daemon --console=plain\n')
                        temp_bat = f.name

                    try:
                        result = subprocess.run(
                            [temp_bat],
                            capture_output=True,
                            text=True,
                            timeout=300,
                            shell=True,
                            env={**os.environ, "TERM": "dumb"}
                        )
                        return result.returncode == 0, result.stdout, result.stderr
                    finally:
                        os.unlink(temp_bat)

            except Exception as fallback_e:
                return False, "", f"All execution methods failed. Original: {str(e)}, Fallback: {str(fallback_e)}"

    def parse_java_compilation_errors(self, output: str) -> List[Dict]:
        """Parse Java compilation errors from Gradle output"""
        errors = []

        # Pattern for Java compilation errors
        # Example: /path/to/File.java:42: error: cannot find symbol
        java_error_pattern = re.compile(
            r'(?P<file>[^\s:]+\.java):(?P<line>\d+):\s*(?:error|错误):\s*(?P<message>.*?)(?=\n[^\s]|\n\n|\Z)',
            re.MULTILINE | re.DOTALL
        )

        for match in java_error_pattern.finditer(output):
            file_path = match.group('file')
            line_num = int(match.group('line'))
            error_msg = match.group('message').strip()

            # Extract additional context (the code snippet showing the error)
            context_start = match.end()
            context_lines = []

            # Safely extract context lines
            remaining_output = output[context_start:]
            lines = remaining_output.split('\n') if remaining_output else []

            for line in lines[:10]:  # Look at next 10 lines for context
                # Stop if we hit another error or a task boundary
                if re.match(r'^[>\s]*\S+\.java:\d+:', line) or line.startswith('> Task'):
                    break
                if line.strip():
                    context_lines.append(line)

            errors.append({
                'file': file_path,
                'line': line_num,
                'message': error_msg,
                'context': '\n'.join(context_lines) if context_lines else '',
                'type': 'compilation'
            })

        return errors

    def parse_gradle_errors(self, output: str) -> List[Dict]:
        """Parse Gradle-specific errors"""
        errors = []

        # Pattern for Gradle errors
        gradle_error_patterns = [
            # Build script errors
            re.compile(r'Build file \'(?P<file>[^\']+)\' line: (?P<line>\d+).*?(?P<message>.*?)(?=\n\n|\Z)', re.DOTALL),
            # General execution failed
            re.compile(r'Execution failed for task \'(?P<task>[^\']+)\'.*?(?P<message>.*?)(?=\n\n|\Z)', re.DOTALL),
        ]

        for pattern in gradle_error_patterns:
            for match in pattern.finditer(output):
                error_dict = {
                    'type': 'gradle',
                    'message': match.group('message').strip()
                }

                if 'file' in match.groupdict():
                    error_dict['file'] = match.group('file')
                if 'line' in match.groupdict():
                    error_dict['line'] = int(match.group('line'))
                if 'task' in match.groupdict():
                    error_dict['task'] = match.group('task')

                errors.append(error_dict)

        return errors

    def read_source_file(self, file_path: str, error_line: int = None) -> Optional[str]:
        """Read source file content, optionally highlighting error line"""
        try:
            # First try the path as given
            full_path = Path(file_path)
            if not full_path.is_absolute():
                full_path = self.project_root / file_path

            # If not found, search common Minecraft mod source directories
            if not full_path.exists():
                search_dirs = [
                    'src/main/java',
                    'src/test/java',
                    'src/client/java',
                    'src/main/kotlin',
                    'src/api/java',
                    'common/src/main/java',  # Multi-loader projects
                    'forge/src/main/java',   # Forge specific
                    'fabric/src/main/java',  # Fabric specific
                ]

                for src_dir in search_dirs:
                    # Try to reconstruct the path based on package structure
                    if '/' in file_path or '\\' in file_path:
                        # Get just the Java package path part
                        parts = Path(file_path).parts
                        java_idx = next((i for i, p in enumerate(parts) if p == 'java'), -1)
                        if java_idx >= 0 and java_idx < len(parts) - 1:
                            package_path = Path(*parts[java_idx + 1:])
                        else:
                            package_path = Path(file_path)
                    else:
                        package_path = Path(file_path)

                    potential_path = self.project_root / src_dir / package_path
                    if potential_path.exists():
                        full_path = potential_path
                        break

            if not full_path.exists():
                return None

            with open(full_path, 'r', encoding='utf-8', errors='replace') as f:
                lines = f.readlines()

            if error_line and 0 < error_line <= len(lines):
                # Add line numbers and highlight error line
                numbered_lines = []
                start = max(0, error_line - 10)
                end = min(len(lines), error_line + 10)

                for i in range(start, end):
                    line_num = i + 1
                    prefix = ">>>" if line_num == error_line else "   "
                    numbered_lines.append(f"{prefix} {line_num:4d}: {lines[i].rstrip()}")

                return '\n'.join(numbered_lines)
            else:
                return ''.join(lines)

        except Exception as e:
            return f"Error reading file: {str(e)}"

    def format_error_report(self, errors: List[Dict]) -> str:
        """Format errors into a comprehensive report for Claude"""
        if not errors:
            return "Build succeeded with no errors!"

        report = ["# Build Error Report\n"]
        report.append(f"Found {len(errors)} error(s) during build:\n")

        for i, error in enumerate(errors, 1):
            report.append(f"\n## Error {i}")
            report.append(f"**Type:** {error.get('type', 'unknown')}")

            if 'file' in error:
                report.append(f"**File:** `{error['file']}`")
            if 'line' in error:
                report.append(f"**Line:** {error['line']}")
            if 'task' in error:
                report.append(f"**Task:** {error['task']}")

            report.append(f"\n**Error Message:**")
            report.append("```")
            report.append(error['message'])
            if 'context' in error and error['context']:
                report.append("\nContext:")
                report.append(error['context'])
            report.append("```")

            # Include source file content for compilation errors
            if error.get('type') == 'compilation' and 'file' in error:
                source_content = self.read_source_file(error['file'], error.get('line'))
                if source_content:
                    report.append(f"\n**Source Code ({error['file']}):**")
                    report.append("```java")
                    report.append(source_content)
                    report.append("```")

        return '\n'.join(report)

    def save_error_report(self, report: str, errors: List[Dict]):
        """Save error report to file for Claude Code to read"""
        try:
            report_path = BUILD_OUTPUT_DIR / "build_error_report.md"
            with open(report_path, 'w', encoding='utf-8') as f:
                f.write(report)
            print(f"\nError report saved to: {report_path}")
        except Exception as e:
            print(f"\nWarning: Could not save error report: {e}")
            print("Error report content printed above.")

        try:
            # Also save structured error data as JSON
            json_path = BUILD_OUTPUT_DIR / "build_errors.json"

            # Clean up errors for JSON serialization
            clean_errors = []
            for error in errors:
                clean_error = {}
                for key, value in error.items():
                    # Ensure all values are JSON serializable
                    if isinstance(value, (str, int, float, bool, type(None))):
                        clean_error[key] = value
                    else:
                        clean_error[key] = str(value)
                clean_errors.append(clean_error)

            with open(json_path, 'w', encoding='utf-8') as f:
                json.dump({
                    'success': False,
                    'error_count': len(clean_errors),
                    'errors': clean_errors
                }, f, indent=2)
            print(f"Structured errors saved to: {json_path}")
        except Exception as e:
            print(f"Warning: Could not save JSON error data: {e}")

    def run(self):
        """Main execution method"""
        success, stdout, stderr = self.run_build()

        if success:
            print("\n[SUCCESS] Build completed successfully!")

            # Save success status
            try:
                json_path = BUILD_OUTPUT_DIR / "build_errors.json"
                with open(json_path, 'w', encoding='utf-8') as f:
                    json.dump({'success': True, 'error_count': 0, 'errors': []}, f, indent=2)
            except Exception as e:
                print(f"Warning: Could not save success status: {e}")

            return 0
        else:
            print("\n[FAILED] Build failed!")

            # Combine stdout and stderr for error parsing
            full_output = stdout + "\n" + stderr

            # Parse different types of errors
            java_errors = self.parse_java_compilation_errors(full_output)
            gradle_errors = self.parse_gradle_errors(full_output)

            all_errors = java_errors + gradle_errors

            if not all_errors:
                # If no specific errors were parsed, create a general error
                # Prioritize stderr, but include stdout if stderr is empty
                error_content = stderr.strip() if stderr.strip() else stdout.strip()
                if not error_content:
                    error_content = "Build failed with no output"

                # Take last 2000 chars to ensure we get meaningful content
                if len(error_content) > 2000:
                    error_content = "..." + error_content[-2000:]

                all_errors = [{
                    'type': 'general',
                    'message': error_content
                }]

            # Generate and save error report
            report = self.format_error_report(all_errors)
            self.save_error_report(report, all_errors)

            print("\n" + report)

            return 1


def main():
    """Main entry point"""
    # Allow specifying project root as command line argument
    project_root = sys.argv[1] if len(sys.argv) > 1 else None

    builder = MinecraftModBuilder(project_root)
    return builder.run()


if __name__ == "__main__":
    sys.exit(main())