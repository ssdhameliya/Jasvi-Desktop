#!/usr/bin/env python3
r"""
Jasvi Ecosystem Universal Synchronization Utility
Synchronizes dse-erp-master-protocol.md, oracle.md, and optionally repository source code
across all 4 ecosystem repositories and tiers:
  - Tier 1: D:\Antigravity-Dev\<Repo>
  - Tier 2: D:\JavaProject\<Repo> (strictly protecting .idea)
  - Tier 3: D:\GitHub\ReleaseSource-<Repo>
  - Tier 4: D:\GitHub\<Repo>
  - Global Antigravity Config: C:\\Users\\JATIN DHAMELIYA\\.gemini\\config\\rules\\dse-erp-master-protocol.md

Usage:
  python scripts/sync-to-all.py               # Syncs master protocol and oracle.md everywhere
  python scripts/sync-to-all.py --code        # Also syncs active code from Tier 1 -> Tier 2 & Tier 3
  python scripts/sync-to-all.py --code --all  # Syncs active code from Tier 1 -> Tier 2, Tier 3, and Tier 4
"""

import sys
import os
import shutil
import subprocess
from pathlib import Path

REPOS = ["Jasvi-Desktop", "Jasvi-Admin", "Jasvi-UserView", "Jasvi-Mobile"]

SOURCE_ROOT = Path(r"D:\Antigravity-Dev\Jasvi-Desktop")
PROTOCOL_FILE = SOURCE_ROOT / "dse-erp-master-protocol.md"
ORACLE_FILE = SOURCE_ROOT / "oracle.md"
GLOBAL_RULE = Path(r"C:\Users\JATIN DHAMELIYA\.gemini\config\rules\dse-erp-master-protocol.md")
GLOBAL_GEMINI = Path(r"C:\Users\JATIN DHAMELIYA\.gemini\config\GEMINI.md")

TARGET_DIRS = [
    # Tier 1
    Path(r"D:\Antigravity-Dev\Jasvi-Admin"),
    Path(r"D:\Antigravity-Dev\Jasvi-UserView"),
    Path(r"D:\Antigravity-Dev\Jasvi-Mobile"),
    # Tier 2
    Path(r"D:\JavaProject\Jasvi-Desktop"),
    Path(r"D:\JavaProject\Jasvi-Admin"),
    Path(r"D:\JavaProject\Jasvi-UserView"),
    Path(r"D:\JavaProject\Jasvi-Mobile"),
    # Tier 3
    Path(r"D:\GitHub\ReleaseSource-Desktop"),
    Path(r"D:\GitHub\ReleaseSource-Jasvi-Desktop"),
    Path(r"D:\GitHub\ReleaseSource-Admin"),
    Path(r"D:\GitHub\ReleaseSource-Jasvi-Admin"),
    Path(r"D:\GitHub\ReleaseSource-UserView"),
    Path(r"D:\GitHub\ReleaseSource-Mobile"),
    # Tier 4
    Path(r"D:\GitHub\Jasvi-Desktop"),
    Path(r"D:\GitHub\Jasvi-Admin"),
    Path(r"D:\GitHub\Jasvi-UserView"),
    Path(r"D:\GitHub\Jasvi-Mobile"),
]

def sync_protocol_and_docs():
    print("=" * 70)
    print("JASVI ECOSYSTEM PROTOCOL & DOCS UNIVERSAL SYNCHRONIZATION")
    print("=" * 70)

    if not PROTOCOL_FILE.exists():
        print(f"ERROR: Source protocol file not found at {PROTOCOL_FILE}")
        sys.exit(1)

    synced_count = 0

    # 1. Sync global rules
    if GLOBAL_RULE.parent.exists():
        shutil.copy2(PROTOCOL_FILE, GLOBAL_RULE)
        print(f"[OK] Global Rule: {GLOBAL_RULE}")
        synced_count += 1

    # 2. Sync to all existing target repository directories
    for target in TARGET_DIRS:
        if target.exists():
            dest_protocol = target / "dse-erp-master-protocol.md"
            shutil.copy2(PROTOCOL_FILE, dest_protocol)

            if ORACLE_FILE.exists():
                dest_oracle = target / "oracle.md"
                shutil.copy2(ORACLE_FILE, dest_oracle)

            print(f"[OK] Synced to: {target}")
            synced_count += 1

    print(f"\nProtocol & docs synchronized successfully across {synced_count} locations.")

def sync_code(repo_name="Jasvi-Desktop", include_tier4=False):
    print("\n" + "=" * 70)
    print(f"JASVI ECOSYSTEM CODE SYNCHRONIZATION: {repo_name}")
    print("=" * 70)

    src = Path(f"D:\\Antigravity-Dev\\{repo_name}")
    if not src.exists():
        print(f"ERROR: Source repo not found at {src}")
        return

    targets = [
        ("Tier 2 (IntelliJ)", Path(f"D:\\JavaProject\\{repo_name}")),
        ("Tier 3 (Staging)", Path(f"D:\\GitHub\\ReleaseSource-{repo_name.replace('Jasvi-', '')}")),
    ]

    if include_tier4:
        targets.append(("Tier 4 (GitHub)", Path(f"D:\\GitHub\\{repo_name}")))

    for label, dst in targets:
        if not dst.exists():
            print(f"[SKIP] Destination does not exist: {dst}")
            continue

        print(f"Syncing {label}: {src} -> {dst}")
        cmd = [
            "robocopy",
            str(src),
            str(dst),
            "/MIR",
            "/COPY:DAT",
            "/R:1",
            "/W:1",
            "/XD", ".git", ".idea", "target", "node_modules", "build", ".gradle"
        ]
        result = subprocess.run(cmd, capture_output=True, text=True)
        # Robocopy return codes 0-7 mean success
        if result.returncode <= 7:
            print(f"[OK] {label} synced successfully (exit code {result.returncode})")
        else:
            print(f"[ERROR] {label} robocopy failed with exit code {result.returncode}")

def main():
    sync_protocol_and_docs()

    if "--code" in sys.argv:
        include_tier4 = "--all" in sys.argv
        repo = "Jasvi-Desktop"
        for i, arg in enumerate(sys.argv):
            if arg == "--repo" and i + 1 < len(sys.argv):
                repo = sys.argv[i + 1]
        sync_code(repo_name=repo, include_tier4=include_tier4)

if __name__ == "__main__":
    main()
