#!/usr/bin/env python3
r"""
Jasvi Ecosystem Master License Studio & Super Admin CLI
Usage:
  python scripts/license_studio.py machine
  python scripts/license_studio.py issue --machine <ID> --company "Shreeji Steel" --days 365 --tier GOLD
  python scripts/license_studio.py verify --file jasvi.lic
"""

import sys
import os
import subprocess
from pathlib import Path

SHARED_DIR = Path(__file__).resolve().parent.parent / "shared"

def run_java_cli(args):
    joined_args = ' '.join(args).replace('"', '\\"')
    cmd = [
        "mvn", "-f", str(SHARED_DIR / "pom.xml"),
        "compile", "exec:java",
        f"-Dexec.mainClass=org.example.shared.licensing.LicenseStudioCli",
        f'-Dexec.args="{joined_args}"'
    ]
    subprocess.run(" ".join(cmd), shell=True, check=True)

def main():
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help"):
        print(__doc__)
        return

    cmd = sys.argv[1].lower()
    if cmd == "machine":
        run_java_cli(["machine"])
    elif cmd == "issue":
        # python scripts/license_studio.py issue --machine <ID> --company "Name" --days 365 --tier GOLD
        machine = "LOCAL"
        company = "JASVI INDUSTRIES"
        days = "365"
        tier = "GOLD"
        out_file = "jasvi.lic"

        i = 2
        while i < len(sys.argv):
            arg = sys.argv[i]
            if arg == "--machine" and i + 1 < len(sys.argv):
                machine = sys.argv[i + 1]
                i += 2
            elif arg == "--company" and i + 1 < len(sys.argv):
                company = sys.argv[i + 1]
                i += 2
            elif arg == "--days" and i + 1 < len(sys.argv):
                days = sys.argv[i + 1]
                i += 2
            elif arg == "--tier" and i + 1 < len(sys.argv):
                tier = sys.argv[i + 1]
                i += 2
            elif arg == "--out" and i + 1 < len(sys.argv):
                out_file = sys.argv[i + 1]
                i += 2
            else:
                i += 1

        run_java_cli(["issue", f'"{machine}"', f'"{company}"', days, tier, f'"{out_file}"'])
    elif cmd == "verify":
        f = sys.argv[2] if len(sys.argv) > 2 else "jasvi.lic"
        run_java_cli(["verify", f'"{f}"'])
    else:
        print(f"Unknown command: {cmd}")
        print(__doc__)

if __name__ == "__main__":
    main()
