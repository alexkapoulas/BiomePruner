#!/usr/bin/env python3
"""
BiomePruner Log Parser Script

This script parses Minecraft log files to extract:
1. Warnings, errors, and fatal errors
2. BiomePruner-specific messages

It creates separate output files for each category to help with debugging and monitoring.

Usage:
  python log_parser.py [--log-type latest|debug|both] [--output-dir OUTPUT_DIR]

Options:
  --log-type TYPE    Which log file to parse: 'latest', 'debug', or 'both' (default: both)
  --output-dir DIR   Directory to save parsed output files (default: claude_tooling/log_output)
"""

import sys
import os
import re
import argparse
import json
from pathlib import Path
from datetime import datetime
from typing import List, Dict, Any, Optional


# Configuration
PROJECT_ROOT = Path(__file__).parent.parent.parent
LOG_OUTPUT_DIR = Path(__file__).parent.parent / "log_output"
INSTANCE_PATH = Path(r"C:\Users\Alex\AppData\Roaming\PrismLauncher\instances\BiomePruner\minecraft")
LOG_FILES = {
    "latest": INSTANCE_PATH / "logs" / "latest.log",
    "debug": INSTANCE_PATH / "logs" / "debug.log"
}


class MinecraftLogParser:
    """Parses Minecraft logs to extract errors, warnings, and BiomePruner messages."""
    
    def __init__(self, log_path: Path):
        self.log_path = log_path
        self.log_name = log_path.stem if log_path.exists() else "unknown"
        self.entries = []
        self.biomepruner_messages = []

    def parse_log(self) -> bool:
        """Parse the log file and extract all relevant entries"""
        if not self.log_path.exists():
            print(f"[ERROR] Log file not found: {self.log_path}")
            return False

        try:
            with open(self.log_path, 'r', encoding='utf-8', errors='replace') as f:
                lines = f.readlines()
        except Exception as e:
            print(f"[ERROR] Failed to read log file {self.log_path}: {e}")
            return False

        # Pattern to match log entries with different levels
        # Matches: [timestamp] [thread/LEVEL] (Mod/Class): message
        log_pattern = re.compile(r'^\[([^\]]+)\]\s*\[([^\]]+)/(WARN|ERROR|FATAL)\]')
        biomepruner_pattern = re.compile(r'biomepruner|BiomePruner', re.IGNORECASE)
        
        current_entry = None
        line_number = 0

        for i, line in enumerate(lines):
            line_number = i + 1
            line_stripped = line.rstrip()
            
            # Check for BiomePruner messages (regardless of log level)
            if biomepruner_pattern.search(line_stripped):
                self.biomepruner_messages.append({
                    'line_number': line_number,
                    'content': line_stripped
                })
            
            # Check for error/warning entries
            match = log_pattern.match(line_stripped)

            if match:
                # Save previous entry if exists
                if current_entry:
                    self.entries.append(current_entry)

                # Start new entry
                current_entry = {
                    'line_number': line_number,
                    'timestamp': match.group(1),
                    'thread_category': match.group(2),
                    'level': match.group(3),
                    'content': [line_stripped],
                    'is_biomepruner': bool(biomepruner_pattern.search(line_stripped))
                }
            elif current_entry:
                # Continue collecting lines for current entry
                # Stop if we hit another timestamped line (even if not error/warn)
                if line_stripped.startswith('[') and '] [' in line_stripped:
                    self.entries.append(current_entry)
                    current_entry = None
                else:
                    current_entry['content'].append(line_stripped)
                    # Update BiomePruner flag if this continuation line mentions it
                    if biomepruner_pattern.search(line_stripped):
                        current_entry['is_biomepruner'] = True

        # Don't forget the last entry
        if current_entry:
            self.entries.append(current_entry)

        print(f"[INFO] Parsed {self.log_name} log: {len(self.entries)} error/warning entries, {len(self.biomepruner_messages)} BiomePruner messages")
        return True

    def generate_errors_report(self) -> str:
        """Generate a markdown report with all warnings, errors, and fatal errors"""
        if not self.entries:
            return f"# Minecraft Log Errors and Warnings ({self.log_name})\n\nNo warnings, errors, or fatal errors found in the log file.\n"

        report = []
        report.append(f"# Minecraft Log Errors and Warnings ({self.log_name})\n")
        report.append(f"**Log File:** `{self.log_path}`")
        report.append(f"**Parsed at:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
        report.append(f"**Total Issues:** {len(self.entries)}")
        
        # Count by level
        level_counts = {}
        biomepruner_count = 0
        for entry in self.entries:
            level = entry['level']
            level_counts[level] = level_counts.get(level, 0) + 1
            if entry['is_biomepruner']:
                biomepruner_count += 1
        
        report.append(f"**Breakdown:** " + ", ".join([f"{level}: {count}" for level, count in sorted(level_counts.items())]))
        report.append(f"**BiomePruner Related:** {biomepruner_count}")
        report.append("\n---\n")

        for entry in self.entries:
            # Add BiomePruner marker if relevant
            biomepruner_marker = " 🔧 **BiomePruner**" if entry['is_biomepruner'] else ""
            
            report.append(f"## Line {entry['line_number']}: {entry['level']}{biomepruner_marker}")
            report.append(f"**Time:** {entry['timestamp']}")
            report.append(f"**Thread/Category:** {entry['thread_category']}\n")
            report.append("```")
            report.extend(entry['content'])
            report.append("```\n")

        return '\n'.join(report)

    def generate_biomepruner_report(self) -> str:
        """Generate a report with all BiomePruner-related messages"""
        if not self.biomepruner_messages:
            return f"# BiomePruner Messages ({self.log_name})\n\nNo BiomePruner-related messages found in the log file.\n"

        report = []
        report.append(f"# BiomePruner Messages ({self.log_name})\n")
        report.append(f"**Log File:** `{self.log_path}`")
        report.append(f"**Parsed at:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
        report.append(f"**Total Messages:** {len(self.biomepruner_messages)}")
        report.append("\n---\n")

        for msg in self.biomepruner_messages:
            report.append(f"**Line {msg['line_number']}:**")
            report.append("```")
            report.append(msg['content'])
            report.append("```\n")

        return '\n'.join(report)

    def export_json(self) -> Dict[str, Any]:
        """Export parsed data as JSON for further processing"""
        return {
            'log_file': str(self.log_path),
            'log_name': self.log_name,
            'parsed_at': datetime.now().isoformat(),
            'error_entries': self.entries,
            'biomepruner_messages': self.biomepruner_messages,
            'summary': {
                'total_errors_warnings': len(self.entries),
                'total_biomepruner_messages': len(self.biomepruner_messages),
                'biomepruner_errors_warnings': sum(1 for entry in self.entries if entry['is_biomepruner']),
                'level_breakdown': {
                    level: sum(1 for entry in self.entries if entry['level'] == level)
                    for level in ['WARN', 'ERROR', 'FATAL']
                }
            }
        }


def create_output_directory():
    """Create the output directory if it doesn't exist"""
    LOG_OUTPUT_DIR.mkdir(exist_ok=True)
    print(f"[INFO] Output directory: {LOG_OUTPUT_DIR}")


def parse_single_log(log_type: str) -> Optional[MinecraftLogParser]:
    """Parse a single log file and return the parser"""
    log_path = LOG_FILES.get(log_type)
    if not log_path:
        print(f"[ERROR] Unknown log type: {log_type}")
        return None
    
    if not log_path.exists():
        print(f"[WARN] Log file not found: {log_path}")
        return None
    
    print(f"[INFO] Parsing {log_type} log: {log_path}")
    parser = MinecraftLogParser(log_path)
    
    if parser.parse_log():
        return parser
    else:
        return None


def save_reports(parser: MinecraftLogParser, output_dir: Path):
    """Save all reports for a parsed log"""
    log_name = parser.log_name
    
    # Save errors report
    errors_report = parser.generate_errors_report()
    errors_file = output_dir / f"{log_name}_errors_warnings.md"
    with open(errors_file, 'w', encoding='utf-8') as f:
        f.write(errors_report)
    print(f"[INFO] Saved errors/warnings report: {errors_file}")
    
    # Save BiomePruner report
    biomepruner_report = parser.generate_biomepruner_report()
    biomepruner_file = output_dir / f"{log_name}_biomepruner_messages.md"
    with open(biomepruner_file, 'w', encoding='utf-8') as f:
        f.write(biomepruner_report)
    print(f"[INFO] Saved BiomePruner messages report: {biomepruner_file}")
    
    # Save JSON data
    json_data = parser.export_json()
    json_file = output_dir / f"{log_name}_parsed_data.json"
    with open(json_file, 'w', encoding='utf-8') as f:
        json.dump(json_data, f, indent=2, ensure_ascii=False)
    print(f"[INFO] Saved JSON data: {json_file}")


def main():
    """Main execution function"""
    parser = argparse.ArgumentParser(
        description='BiomePruner Log Parser Script',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python log_parser.py                          # Parse both latest and debug logs
  python log_parser.py --log-type latest        # Parse only latest.log
  python log_parser.py --log-type debug         # Parse only debug.log
  python log_parser.py --output-dir ./my_logs   # Use custom output directory
        """)
    
    parser.add_argument(
        '--log-type',
        choices=['latest', 'debug', 'both'],
        default='both',
        help='Which log file to parse (default: both)'
    )
    
    parser.add_argument(
        '--output-dir',
        type=Path,
        default=LOG_OUTPUT_DIR,
        help='Directory to save parsed output files'
    )
    
    args = parser.parse_args()
    
    print("=== BiomePruner Log Parser ===")
    
    # Create output directory
    output_dir = args.output_dir
    output_dir.mkdir(exist_ok=True)
    print(f"[INFO] Output directory: {output_dir}")
    
    # Check if instance path exists
    if not INSTANCE_PATH.exists():
        print(f"[ERROR] Minecraft instance not found: {INSTANCE_PATH}")
        print("[INFO] Make sure the Minecraft instance path is correct")
        return 1
    
    # Determine which logs to parse
    if args.log_type == 'both':
        log_types = ['latest', 'debug']
    else:
        log_types = [args.log_type]
    
    success_count = 0
    
    # Parse each requested log type
    for log_type in log_types:
        parser_obj = parse_single_log(log_type)
        if parser_obj:
            save_reports(parser_obj, output_dir)
            success_count += 1
    
    # Summary
    print(f"\n=== Parsing Complete ===")
    print(f"Successfully parsed {success_count}/{len(log_types)} log files")
    print(f"Output saved to: {output_dir}")
    
    if success_count == 0:
        print("[ERROR] No log files could be parsed")
        return 1
    elif success_count < len(log_types):
        print("[WARN] Some log files could not be parsed")
        return 2
    else:
        print("[SUCCESS] All requested log files parsed successfully")
        return 0


if __name__ == "__main__":
    exit_code = main()
    sys.exit(exit_code)