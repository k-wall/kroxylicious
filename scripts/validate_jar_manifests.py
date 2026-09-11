#!/usr/bin/env python3
#
# Copyright Kroxylicious Authors.
#
# Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
#

"""Validate JAR manifest entries for implementation metadata.

The parent POM configures maven-jar-plugin with addDefaultImplementationEntries=true,
which should populate Implementation-Title, Implementation-Version, and
Implementation-Vendor in each JAR's manifest. This script validates those entries
are present and optionally checks their values.

Usage:
    python3 scripts/validate_jar_manifests.py <jar-file> [options]

Example:
    python3 scripts/validate_jar_manifests.py \\
        kroxylicious-api/target/kroxylicious-api-0.25.0-SNAPSHOT.jar \\
        --expected-version=0.25.0-SNAPSHOT \\
        --expected-title="Kroxylicious API" \\
        --expected-vendor=Kroxylicious
"""

import argparse
import sys
import zipfile
from pathlib import Path


def parse_manifest(manifest_content):
    """Parse JAR manifest into a dictionary.
    
    Handles line continuations (lines starting with space are continuations).
    """
    attrs = {}
    current_key = None
    current_value = []
    
    for line in manifest_content.split('\n'):
        # Line continuation (starts with space)
        if line.startswith(' ') and current_key:
            current_value.append(line[1:])  # Remove leading space
        # New attribute
        elif ':' in line:
            # Save previous attribute if exists
            if current_key:
                attrs[current_key] = ''.join(current_value)
            # Parse new attribute
            key, value = line.split(':', 1)
            current_key = key.strip()
            current_value = [value.strip()]
        # Empty line or malformed - save current and reset
        elif current_key:
            attrs[current_key] = ''.join(current_value)
            current_key = None
            current_value = []
    
    # Save last attribute
    if current_key:
        attrs[current_key] = ''.join(current_value)
    
    return attrs


def validate_manifest(jar_path, expected_version=None, expected_title=None, expected_vendor=None):
    """Validate JAR manifest contains required implementation entries.
    
    Returns:
        tuple: (success: bool, errors: list[str])
    """
    errors = []
    
    # Check JAR exists
    if not jar_path.exists():
        return False, [f"JAR file not found: {jar_path}"]
    
    # Read manifest
    try:
        with zipfile.ZipFile(jar_path, 'r') as jar:
            manifest_content = jar.read('META-INF/MANIFEST.MF').decode('utf-8')
    except Exception as e:
        return False, [f"Failed to read manifest from {jar_path}: {e}"]
    
    # Parse manifest
    attrs = parse_manifest(manifest_content)
    
    # Validate required attributes are present
    required = ['Implementation-Title', 'Implementation-Version', 'Implementation-Vendor']
    missing = [attr for attr in required if attr not in attrs or not attrs[attr]]
    
    if missing:
        errors.append(f"Missing or empty manifest attributes: {', '.join(missing)}")
    
    # Validate expected values if provided
    if expected_version and attrs.get('Implementation-Version') != expected_version:
        errors.append(
            f"Implementation-Version mismatch: "
            f"expected '{expected_version}', got '{attrs.get('Implementation-Version')}'"
        )
    
    if expected_title and attrs.get('Implementation-Title') != expected_title:
        errors.append(
            f"Implementation-Title mismatch: "
            f"expected '{expected_title}', got '{attrs.get('Implementation-Title')}'"
        )
    
    if expected_vendor and attrs.get('Implementation-Vendor') != expected_vendor:
        errors.append(
            f"Implementation-Vendor mismatch: "
            f"expected '{expected_vendor}', got '{attrs.get('Implementation-Vendor')}'"
        )
    
    return len(errors) == 0, errors


def main():
    parser = argparse.ArgumentParser(
        description='Validate JAR manifest implementation entries',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    parser.add_argument(
        'jar_file',
        type=Path,
        help='Path to JAR file to validate'
    )
    parser.add_argument(
        '--expected-version',
        help='Expected Implementation-Version value'
    )
    parser.add_argument(
        '--expected-title',
        help='Expected Implementation-Title value'
    )
    parser.add_argument(
        '--expected-vendor',
        help='Expected Implementation-Vendor value'
    )
    
    args = parser.parse_args()
    
    success, errors = validate_manifest(
        args.jar_file,
        expected_version=args.expected_version,
        expected_title=args.expected_title,
        expected_vendor=args.expected_vendor
    )
    
    if success:
        print(f"✓ Manifest validation passed: {args.jar_file.name}")
        sys.exit(0)
    else:
        print(f"✗ Manifest validation FAILED for {args.jar_file.name}:")
        for error in errors:
            print(f"  - {error}")
        sys.exit(1)


if __name__ == '__main__':
    main()
