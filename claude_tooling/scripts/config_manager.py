#!/usr/bin/env python3
"""
BiomePruner Configuration Manager

A utility script for reading and writing BiomePruner mod configuration values.
Provides safe access to all configuration sections except the testing section.

Usage:
    python config_manager.py read [section] [key]     # Read a config value
    python config_manager.py write [section] [key] [value]  # Write a config value
    python config_manager.py list [section]          # List all keys in a section
    python config_manager.py sections               # List all available sections
    python config_manager.py backup                 # Create a backup of current config
    python config_manager.py restore [backup_file]  # Restore config from backup

Examples:
    python config_manager.py read general microBiomeThreshold
    python config_manager.py write general microBiomeThreshold 75
    python config_manager.py list biome_blacklist
    python config_manager.py sections
"""

import argparse
import sys
import toml
from pathlib import Path
from datetime import datetime
import json
import shutil

# Configuration paths
PROJECT_ROOT = Path(__file__).parent.parent.parent
CONFIG_FILE = Path("C:/Users/Alex/AppData/Roaming/PrismLauncher/instances/BiomePruner/minecraft/config/biomepruner-common.toml")
BACKUP_DIR = PROJECT_ROOT / "claude_tooling" / "config_backups"

# Protected sections that Claude cannot modify
PROTECTED_SECTIONS = {"testing"}

# Valid section names (excluding testing)
VALID_SECTIONS = {"general", "biome_blacklist", "performance", "heightmap"}

def ensure_dependencies():
    """Ensure required dependencies are available."""
    try:
        import toml
        return True
    except ImportError:
        print("[ERROR] Missing required dependency: toml")
        print("Install with: pip install toml")
        return False

def load_config():
    """Load the configuration file."""
    if not CONFIG_FILE.exists():
        print(f"[ERROR] Configuration file not found: {CONFIG_FILE}")
        return None
    
    try:
        with open(CONFIG_FILE, 'r') as f:
            return toml.load(f)
    except Exception as e:
        print(f"[ERROR] Failed to load configuration: {e}")
        return None

def save_config(config):
    """Save the configuration file."""
    try:
        with open(CONFIG_FILE, 'w') as f:
            toml.dump(config, f)
        return True
    except Exception as e:
        print(f"[ERROR] Failed to save configuration: {e}")
        return False

def validate_section(section):
    """Validate that the section is allowed to be modified."""
    if section in PROTECTED_SECTIONS:
        print(f"[ERROR] Section '{section}' is protected and cannot be modified")
        print(f"Protected sections: {', '.join(PROTECTED_SECTIONS)}")
        return False
    
    if section not in VALID_SECTIONS:
        print(f"[ERROR] Invalid section '{section}'")
        print(f"Valid sections: {', '.join(VALID_SECTIONS)}")
        return False
    
    return True

def parse_value(value_str, current_value=None):
    """Parse a string value to the appropriate type."""
    if current_value is not None:
        # Try to match the type of the current value
        if isinstance(current_value, bool):
            if value_str.lower() in ('true', '1', 'yes', 'on'):
                return True
            elif value_str.lower() in ('false', '0', 'no', 'off'):
                return False
            else:
                raise ValueError(f"Invalid boolean value: {value_str}")
        elif isinstance(current_value, int):
            return int(value_str)
        elif isinstance(current_value, float):
            return float(value_str)
        elif isinstance(current_value, list):
            # Parse as JSON array
            try:
                parsed = json.loads(value_str)
                if isinstance(parsed, list):
                    return parsed
                else:
                    raise ValueError("Expected array format")
            except:
                # Try parsing as comma-separated values
                return [item.strip() for item in value_str.split(',')]
    
    # Auto-detect type
    # Try boolean first
    if value_str.lower() in ('true', 'false'):
        return value_str.lower() == 'true'
    
    # Try integer
    try:
        return int(value_str)
    except ValueError:
        pass
    
    # Try float
    try:
        return float(value_str)
    except ValueError:
        pass
    
    # Try JSON (for arrays/objects)
    try:
        return json.loads(value_str)
    except:
        pass
    
    # Default to string
    return value_str

def cmd_read(args):
    """Read a configuration value."""
    config = load_config()
    if config is None:
        return False
    
    if len(args) < 2:
        print("[ERROR] Usage: read [section] [key]")
        return False
    
    section, key = args[0], args[1]
    
    if not validate_section(section):
        return False
    
    if section not in config:
        print(f"[ERROR] Section '{section}' not found in configuration")
        return False
    
    if key not in config[section]:
        print(f"[ERROR] Key '{key}' not found in section '{section}'")
        return False
    
    value = config[section][key]
    print(f"{section}.{key} = {json.dumps(value, indent=2) if isinstance(value, (list, dict)) else value}")
    return True

def cmd_write(args):
    """Write a configuration value."""
    config = load_config()
    if config is None:
        return False
    
    if len(args) < 3:
        print("[ERROR] Usage: write [section] [key] [value]")
        return False
    
    section, key = args[0], args[1]
    value_str = ' '.join(args[2:])  # Allow values with spaces
    
    if not validate_section(section):
        return False
    
    if section not in config:
        print(f"[ERROR] Section '{section}' not found in configuration")
        return False
    
    if key not in config[section]:
        print(f"[ERROR] Key '{key}' not found in section '{section}'")
        return False
    
    # Get current value for type inference
    current_value = config[section][key]
    
    try:
        new_value = parse_value(value_str, current_value)
        old_value = config[section][key]
        config[section][key] = new_value
        
        if save_config(config):
            print(f"[SUCCESS] Updated {section}.{key}")
            print(f"  Old value: {json.dumps(old_value, indent=2) if isinstance(old_value, (list, dict)) else old_value}")
            print(f"  New value: {json.dumps(new_value, indent=2) if isinstance(new_value, (list, dict)) else new_value}")
            return True
        else:
            return False
    except Exception as e:
        print(f"[ERROR] Failed to parse value '{value_str}': {e}")
        return False

def cmd_list(args):
    """List all keys in a section."""
    config = load_config()
    if config is None:
        return False
    
    if len(args) < 1:
        print("[ERROR] Usage: list [section]")
        return False
    
    section = args[0]
    
    if not validate_section(section):
        return False
    
    if section not in config:
        print(f"[ERROR] Section '{section}' not found in configuration")
        return False
    
    print(f"Configuration keys in section '{section}':")
    for key, value in config[section].items():
        value_preview = json.dumps(value, indent=2) if isinstance(value, (list, dict)) else str(value)
        if len(value_preview) > 50:
            value_preview = value_preview[:50] + "..."
        print(f"  {key} = {value_preview}")
    
    return True

def cmd_sections(args):
    """List all available sections."""
    config = load_config()
    if config is None:
        return False
    
    print("Available configuration sections:")
    for section in sorted(config.keys()):
        if section in PROTECTED_SECTIONS:
            print(f"  {section} (protected - read-only)")
        elif section in VALID_SECTIONS:
            print(f"  {section}")
        else:
            print(f"  {section} (unknown)")
    
    print(f"\nNote: Protected sections ({', '.join(PROTECTED_SECTIONS)}) cannot be modified")
    return True

def cmd_backup(args):
    """Create a backup of the current configuration."""
    if not CONFIG_FILE.exists():
        print(f"[ERROR] Configuration file not found: {CONFIG_FILE}")
        return False
    
    # Ensure backup directory exists
    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    
    # Create timestamped backup filename
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_file = BACKUP_DIR / f"biomepruner-config-backup_{timestamp}.toml"
    
    try:
        shutil.copy2(CONFIG_FILE, backup_file)
        print(f"[SUCCESS] Configuration backed up to: {backup_file}")
        return True
    except Exception as e:
        print(f"[ERROR] Failed to create backup: {e}")
        return False

def cmd_restore(args):
    """Restore configuration from a backup."""
    if len(args) < 1:
        print("[ERROR] Usage: restore [backup_file]")
        print(f"Available backups in {BACKUP_DIR}:")
        if BACKUP_DIR.exists():
            backups = list(BACKUP_DIR.glob("biomepruner-config-backup_*.toml"))
            if backups:
                for backup in sorted(backups, reverse=True):
                    print(f"  {backup.name}")
            else:
                print("  No backups found")
        return False
    
    backup_file = Path(args[0])
    if not backup_file.is_absolute():
        backup_file = BACKUP_DIR / backup_file
    
    if not backup_file.exists():
        print(f"[ERROR] Backup file not found: {backup_file}")
        return False
    
    try:
        # Validate backup file first
        with open(backup_file, 'r') as f:
            toml.load(f)
        
        # Create a backup of current config before restoring
        if CONFIG_FILE.exists():
            cmd_backup([])
        
        # Restore the backup
        shutil.copy2(backup_file, CONFIG_FILE)
        print(f"[SUCCESS] Configuration restored from: {backup_file}")
        return True
    except Exception as e:
        print(f"[ERROR] Failed to restore backup: {e}")
        return False

def main():
    """Main entry point."""
    if not ensure_dependencies():
        return 1
    
    parser = argparse.ArgumentParser(
        description="BiomePruner Configuration Manager",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
    python config_manager.py read general microBiomeThreshold
    python config_manager.py write general microBiomeThreshold 75
    python config_manager.py list biome_blacklist
    python config_manager.py sections
    python config_manager.py backup
    python config_manager.py restore biomepruner-config-backup_20250723_140000.toml
        """
    )
    
    parser.add_argument('command', choices=['read', 'write', 'list', 'sections', 'backup', 'restore'],
                       help='Command to execute')
    parser.add_argument('args', nargs='*', help='Command arguments')
    
    args = parser.parse_args()
    
    # Execute command
    command_map = {
        'read': cmd_read,
        'write': cmd_write,
        'list': cmd_list,
        'sections': cmd_sections,
        'backup': cmd_backup,
        'restore': cmd_restore,
    }
    
    try:
        success = command_map[args.command](args.args)
        return 0 if success else 1
    except KeyboardInterrupt:
        print("\n[INFO] Operation cancelled by user")
        return 1
    except Exception as e:
        print(f"[ERROR] Unexpected error: {e}")
        return 1

if __name__ == "__main__":
    sys.exit(main())