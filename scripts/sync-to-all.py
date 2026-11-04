#!/usr/bin/env python3
r"""
Jasvi Ecosystem Universal Synchronization Utility
Synchronizes dse-erp-master-protocol.md, oracle.md, repository source code,
and Google Drive cloud mirrors across all 4 ecosystem repositories and tiers:
  - Tier 1: D:\Antigravity-Dev\<Repo>
  - Tier 2: D:\JavaProject\<Repo> (strictly protecting .idea)
  - Tier 3: D:\GitHub\ReleaseSource-<Repo>
  - Tier 4: D:\GitHub\<Repo>
  - Tier 5: Google Drive Cloud Backup (Folder 'JavaFx')
  - Global Antigravity Config: C:\Users\JATIN DHAMELIYA\.gemini\config\rules\dse-erp-master-protocol.md

Usage:
  python scripts/sync-to-all.py                       # Syncs master protocol and oracle.md everywhere
  python scripts/sync-to-all.py --code                # Also syncs active code from Tier 1 -> Tier 2 & Tier 3
  python scripts/sync-to-all.py --code --all          # Syncs active code from Tier 1 -> Tier 2, Tier 3, and Tier 4
  python scripts/sync-to-all.py --gdrive              # In-place updates Google Drive cloud archives
  python scripts/sync-to-all.py --code --all --gdrive # Complete 5-tier synchronization everywhere
"""

import sys
import os
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

REPOS = ["Jasvi-Desktop", "Jasvi-Admin", "Jasvi-UserView", "Jasvi-Mobile"]

SOURCE_ROOT = Path(r"D:\Antigravity-Dev\Jasvi-Desktop")
PROTOCOL_FILE = SOURCE_ROOT / "dse-erp-master-protocol.md"
ORACLE_FILE = SOURCE_ROOT / "oracle.md"
GLOBAL_RULE = Path(r"C:\Users\JATIN DHAMELIYA\.gemini\config\rules\dse-erp-master-protocol.md")
GLOBAL_GEMINI = Path(r"C:\Users\JATIN DHAMELIYA\.gemini\config\GEMINI.md")

# Google Drive Cloud Mirror Registry (Folder 'JavaFx': 1Nn2wQWrNETBUNHbAAbYriWNeu_nXai7l)
GDRIVE_REGISTRY = {
    "Jasvi-Desktop":  {"folder": "1gBQWeTodjuxQmUHuWzEiE-MoRRC5W7qA", "file": "1oOjUnxuiJhq5SV4iO5UHN1G-oYFEhNwX"},
    "Jasvi-Admin":    {"folder": "10fio8DAwYowAwVM5LyPhe877CXjy5OK3", "file": "13iMvdV7HqqC-whAknCXi6-rUHCvbOOWA"},
    "Jasvi-UserView": {"folder": "1W5yeaVOExL_k5wsYUxVepadRj_MocEcq", "file": "1cmVdRKGrM1FD5q2gk5GDo9mFj5XZUN9I"},
    "Jasvi-Mobile":   {"folder": "104QmrAHnLGHSMrn5zHBPeM-30ZMtOOo4", "file": "1BoGSpdDLZAREpW2Z5D1nwVHZHLu7fQi1"},
}

EXCLUDE_DIRS = {".git", ".idea", "target", "build", ".gradle", "node_modules"}

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

def sync_gdrive(repo_name=None):
    print("\n" + "=" * 70)
    print("JASVI ECOSYSTEM TIER 5: GOOGLE DRIVE CLOUD MIRROR SYNCHRONIZATION")
    print("=" * 70)

    targets = [repo_name] if (repo_name and repo_name in GDRIVE_REGISTRY) else list(GDRIVE_REGISTRY.keys())
    temp_dir = tempfile.gettempdir()

    for rname in targets:
        entry = GDRIVE_REGISTRY.get(rname)
        if not entry:
            continue

        src_dir = Path(f"D:\\Antigravity-Dev\\{rname}")
        if not src_dir.exists():
            print(f"[SKIP] Source repository does not exist: {src_dir}")
            continue

        file_id = entry["file"]
        zip_path = Path(temp_dir) / f"{rname}.zip"

        print(f"Packaging clean archive for {rname} -> {zip_path.name}...")
        total_files = 0
        with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zipf:
            for root, dirs, files in os.walk(src_dir):
                dirs[:] = [d for d in dirs if d.lower() not in EXCLUDE_DIRS]
                for file in files:
                    full_p = os.path.join(root, file)
                    rel_p = os.path.relpath(full_p, src_dir)
                    zipf.write(full_p, rel_p)
                    total_files += 1

        size_mb = os.path.getsize(zip_path) / (1024 * 1024)
        print(f"Updating Google Drive archive (in-place: {file_id}, {total_files} files, {size_mb:.2f} MB)...")

        gdrive_cmd = shutil.which("gdrive") or "gdrive"
        update_cmd = [gdrive_cmd, "mutate", "update", file_id, str(zip_path)]
        try:
            res = subprocess.run(update_cmd, capture_output=True, text=True, check=True, shell=True)
            print(f"[OK] {rname} synchronized to Google Drive: {res.stdout.strip()}")
        except subprocess.CalledProcessError as err:
            print(f"[ERROR] Failed to update Google Drive for {rname}: {err.stderr.strip()}")
        finally:
            if zip_path.exists():
                try:
                    os.remove(zip_path)
                except OSError:
                    pass

def main():
    sync_protocol_and_docs()

    repo = None
    for i, arg in enumerate(sys.argv):
        if arg == "--repo" and i + 1 < len(sys.argv):
            repo = sys.argv[i + 1]

    if "--code" in sys.argv:
        include_tier4 = "--all" in sys.argv
        target_repo = repo or "Jasvi-Desktop"
        sync_code(repo_name=target_repo, include_tier4=include_tier4)

    if "--gdrive" in sys.argv:
        sync_gdrive(repo_name=repo)

if __name__ == "__main__":
    main()
