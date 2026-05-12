"""Recompute SHA-256 hashes of 5 critical ScoreLicense classes from
obfuscated-final7/licensed-ScoreLicense-1.0.0-obf.jar and patch them into:
  - license-guard/src2/com/score/license/guard/LicenseGate.java
  - license-guard/wrapper_template_v2.java
  - ScorePlugins Build/templates/wrapper_template_v2.java
  - ScorePlugins Build/guard-src2/com/score/license/guard/LicenseGate.java

Run AFTER rename_main.py and BEFORE build_v2.py.

Without this step, guards will contain stale hashes and ALL plugins will
fail with "Iltimos haqiqiy ScoreLicense plaginini ornating".
"""
import os, re, sys, zipfile, hashlib

ROOT = "C:/Users/shoxg/OneDrive/Desktop/plugins"
BUILD = "C:/Users/shoxg/OneDrive/Desktop/ScorePlugins Build"
LIC_JAR = os.path.join(ROOT, "obfuscated-final7", "licensed-ScoreLicense-1.0.0-obf.jar")

CLASSES = [
    "com/score/license/LicenseDatabase.class",
    "com/score/license/LicenseStorage.class",
    "com/score/license/LicenseHeartbeat.class",
    "com/score/license/LicenseAPI.class",
    "com/score/license/lIIIll1O0OOIl0IOOl.class",
]

if not os.path.exists(LIC_JAR):
    print(f"!! {LIC_JAR} not found. Did you run rename_main.py?")
    sys.exit(1)

hashes = {}
with zipfile.ZipFile(LIC_JAR) as z:
    names = set(z.namelist())
    for c in CLASSES:
        if c not in names:
            print(f"!! class {c} missing in {LIC_JAR}")
            sys.exit(1)
        hashes[c] = hashlib.sha256(z.read(c)).hexdigest()

print("New hashes:")
for c, h in hashes.items():
    print(f"  {c}: {h}")

# ---- patch LicenseGate.java (EXPECTED.put pattern) ----
def patch_license_gate(path):
    if not os.path.exists(path):
        print(f"  skip (missing): {path}")
        return
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    new = text
    for c, h in hashes.items():
        # match: EXPECTED.put("com/score/license/X.class", "<oldhash>");
        pattern = re.compile(
            r'(EXPECTED\.put\(\s*"' + re.escape(c) + r'"\s*,\s*\n?\s*")[0-9a-f]{64}(")',
            re.MULTILINE,
        )
        new, n = pattern.subn(lambda m: m.group(1) + h + m.group(2), new)
        if n == 0:
            print(f"  WARN: no match for {c} in {path}")
    if new != text:
        with open(path, "w", encoding="utf-8") as f:
            f.write(new)
        print(f"  updated: {path}")

# ---- patch wrapper_template_v2.java (LIC_HASHES array pattern) ----
def patch_wrapper_template(path):
    if not os.path.exists(path):
        print(f"  skip (missing): {path}")
        return
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    new = text
    for c, h in hashes.items():
        # match: {"com/score/license/X.class", "<oldhash>"},
        # using lookbehind: hash literal preceded by class name + comma + ws
        pattern = re.compile(
            r'(\{\s*"' + re.escape(c) + r'"\s*,\s*")[0-9a-f]{64}(")',
            re.MULTILINE,
        )
        new, n = pattern.subn(lambda m: m.group(1) + h + m.group(2), new)
        if n == 0:
            print(f"  WARN: no match for {c} in {path}")
    if new != text:
        with open(path, "w", encoding="utf-8") as f:
            f.write(new)
        print(f"  updated: {path}")

# Patch all known locations (working copies + ScorePlugins Build copies)
TARGETS_GATE = [
    os.path.join(ROOT, "license-guard", "src2", "com", "score", "license", "guard", "LicenseGate.java"),
    os.path.join(BUILD, "guard-src2", "com", "score", "license", "guard", "LicenseGate.java"),
]
TARGETS_TEMPLATE = [
    os.path.join(ROOT, "license-guard", "wrapper_template_v2.java"),
    os.path.join(BUILD, "templates", "wrapper_template_v2.java"),
]

print("\nPatching LicenseGate.java:")
for p in TARGETS_GATE:
    patch_license_gate(p)

print("\nPatching wrapper_template_v2.java:")
for p in TARGETS_TEMPLATE:
    patch_wrapper_template(p)

print("\nDone. Now run build_v2.py.")
