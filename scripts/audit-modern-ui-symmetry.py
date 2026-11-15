#!/usr/bin/env python3
"""Audit asserting 1:1 line symmetry between dark-theme.css and light-theme.css modern UI blocks."""
from pathlib import Path
import sys
from release_version import VERSION

ROOT = Path(__file__).resolve().parents[1]
DARK_CSS = ROOT / "desktop/src/main/resources/css/dark-theme.css"
LIGHT_CSS = ROOT / "desktop/src/main/resources/css/light-theme.css"

def extract_modern_block(path: Path):
    text = path.read_text(encoding="utf-8")
    marker = "MODERN COLORFUL UI AUTHORITY"
    if marker not in text:
        raise AssertionError(f"Missing modern UI marker in {path.name}")
    block_text = text[text.index(marker):]
    return [line.rstrip() for line in block_text.splitlines() if line.strip() != ""]

dark_lines = extract_modern_block(DARK_CSS)
light_lines = extract_modern_block(LIGHT_CSS)

assert len(dark_lines) == len(light_lines), (
    f"Line count mismatch in modern UI block: dark={len(dark_lines)}, light={len(light_lines)}"
)

selectors_count = 0
for idx, (d, l) in enumerate(zip(dark_lines, light_lines)):
    if d.endswith("{"):
        selectors_count += 1
        d_norm = d.replace(".dse-theme-dark", ".dse-theme-light")
        assert d_norm == l, f"Selector mismatch at line {idx+1}:\n  Dark:  {d}\n  Light: {l}"
    if d == "}":
        assert l == "}", f"Closing brace mismatch at line {idx+1}:\n  Dark:  {d}\n  Light: {l}"

full_dark_lines = DARK_CSS.read_text(encoding="utf-8").splitlines()
full_light_lines = LIGHT_CSS.read_text(encoding="utf-8").splitlines()
assert len(full_dark_lines) == len(full_light_lines), (
    f"Full-file line count mismatch: dark={len(full_dark_lines)}, light={len(full_light_lines)}"
)
for idx, (dl, ll) in enumerate(zip(full_dark_lines, full_light_lines), 1):
    ds, ls = dl.strip(), ll.strip()
    assert (ds == "") == (ls == ""), f"Blank line mismatch at line {idx}"
    assert (ds == "}") == (ls == "}"), f"Closing brace mismatch at line {idx}"
    if ds.endswith("{") or ls.endswith("{"):
        assert ds.replace(".dse-theme-dark", ".dse-theme-light") == ls, (
            f"Full-file selector mismatch at line {idx}:\n  Dark:  {ds}\n  Light: {ls}"
        )
    elif ":" in ds and ":" in ls and "{" not in ds and not ds.startswith(("/*", "*")):
        assert ds.split(":", 1)[0].strip() == ls.split(":", 1)[0].strip(), (
            f"Full-file CSS property mismatch at line {idx}:\n  Dark:  {ds}\n  Light: {ls}"
        )

print(f"MODERN_UI_SYMMETRY_OK lines={len(dark_lines)} full_lines={len(full_dark_lines)} selectors={selectors_count} parity=100%")                         