#!/usr/bin/env python3
"""
Minecraft Log Error/Warning Extractor
Extracts all ERROR and WARN level messages from Minecraft logs
"""

import re
from pathlib import Path
from typing import List, Dict

class MinecraftLogParser:
    def __init__(self, log_path: str = r"C:\Users\Alex\AppData\Roaming\PrismLauncher\instances\BiomePruner\minecraft\logs\latest.log"):
        self.log_path = Path(log_path)
        self.entries = []

    def parse_log(self) -> bool:
        """Parse the log file and extract all warnings and errors"""
        if not self.log_path.exists():
            print(f"Log file not found: {self.log_path}")
            return False

        try:
            with open(self.log_path, 'r', encoding='utf-8', errors='replace') as f:
                lines = f.readlines()
        except Exception as e:
            print(f"Error reading log file: {e}")
            return False

        # Pattern to match log entries with WARN or ERROR levels
        # Matches: [timestamp] [category/LEVEL] message
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

    def generate_report(self) -> str:
        """Generate a simple report with all warnings and errors"""
        if not self.entries:
            return "No warnings or errors found in the log file.\n"

        report = []
        report.append(f"# Minecraft Log Errors and Warnings\n")
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

    def save_report(self, project_root: str = "."):
        """Save the report to the project directory"""
        project_path = Path(project_root)
        report_path = project_path / "minecraft_log_errors.md"

        report = self.generate_report()

        try:
            with open(report_path, 'w', encoding='utf-8') as f:
                f.write(report)
            print(f"[SUCCESS] Report saved to: {report_path}")
            print(f"Found {len(self.entries)} warnings/errors")
        except Exception as e:
            print(f"[FAILED] Could not save report: {e}")


def main():
    """Main entry point"""
    parser = MinecraftLogParser()

    print("Extracting errors and warnings from Minecraft log...")
    if parser.parse_log():
        parser.save_report()
    else:
        return 1

    return 0


if __name__ == "__main__":
    import sys
    sys.exit(main())