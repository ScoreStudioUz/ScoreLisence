"""Re-inject the FRESH obfuscated LicenseAPI from bundle-out.jar into ScoreLicense,
plus the Skidfuscator string-decrypt helper class into BOTH ScoreLicense AND every
guard-bearing dependent jar (since each guard also uses encrypted strings).

Then recompute hashes (LicenseAPI changes!) and rebuild guards with the new hash.
"""
import os, zipfile, hashlib, shutil, re

ROOT = "/workspaces/ScoreLisence"
BUNDLE = os.path.join(ROOT, "license-guard/skid-work2/bundle-out.jar")
IN_DIR = os.path.join(ROOT, "obfuscated-final8")  # we will fix in place by writing to final8b first
TMP_DIR = os.path.join(ROOT, "obfuscated-final8b")
os.makedirs(TMP_DIR, exist_ok=True)

# 1) Pull obfuscated artifacts from bundle
with zipfile.ZipFile(BUNDLE, "r") as z:
    bundle_classes = {n: z.read(n) for n in z.namelist() if n.endswith(".class")}

# Identify Skidfuscator helper packages: any class NOT in the source packages
SOURCE_PKGS = (
    "com/score/license/guard/",
    "com/scoreautobuyer/guard/",
    "com/scorepvp/guard/",
    "com/scoretpa/guard/",
    "org/by1337/bairdrop/guard/",
    "ru/score/hwauction/guard/",
    "com/score/license/LicenseAPI.class",  # exact path
)

helpers = {}
fresh_license_api = None
for n, data in bundle_classes.items():
    if n == "com/score/license/LicenseAPI.class":
        # Wait - LicenseAPI was NOT in this bundle (we put guards only). Confirm.
        fresh_license_api = data
        continue
    is_source = any((p.endswith("/") and n.startswith(p)) or n == p for p in SOURCE_PKGS)
    if not is_source:
        helpers[n] = data
        print(f"helper: {n}  ({len(data)} bytes)")

print(f"\nLicenseAPI in bundle: {'YES' if fresh_license_api else 'NO'}")
# bundle did NOT include LicenseAPI source; the guard-bundle obfuscation happened
# on a bundle that excluded LicenseAPI. So we re-use the obfuscated LicenseAPI from
# previous run (in final6) which references ongnajdkreqrrkfg/izocvpdluysygscw - and
# THAT helper lives in skid-work/bundle-out.jar (NOT skid-work2)
PREV_BUNDLE = os.path.join(ROOT, "license-guard/skid-work/bundle-out.jar")
prev_helpers = {}
prev_license_api = None
if os.path.exists(PREV_BUNDLE):
    with zipfile.ZipFile(PREV_BUNDLE, "r") as z:
        for n in z.namelist():
            if not n.endswith(".class"): continue
            if n == "com/score/license/LicenseAPI.class":
                prev_license_api = z.read(n)
            elif not any((p.endswith("/") and n.startswith(p)) or n == p for p in SOURCE_PKGS):
                prev_helpers[n] = z.read(n)
                print(f"prev-helper: {n}  ({len(z.read(n))} bytes)")
    print(f"prev LicenseAPI: {'YES' if prev_license_api else 'NO'}")
else:
    print(f"prev bundle not present (ok): {PREV_BUNDLE}")

# Strategy: keep current obfuscated LicenseAPI (which references ongnajdkreqrrkfg/...)
# and add BOTH helpers (orolmpiksjnvxyut + ongnajdkreqrrkfg) to every jar that has guards.

ALL_HELPERS = {}
ALL_HELPERS.update(helpers)
ALL_HELPERS.update(prev_helpers)
print(f"\nTotal helpers to embed: {len(ALL_HELPERS)}")

JARS_WITH_GUARD = [
    "licensed-ScoreLicense-1.0.0-obf.jar",
    "licensed-ScoreHWAuction-obf.jar",
    "licensed-ScoreAutoBuyer-1.0.0-obf.jar",
    "licensed-ScorePvP-1.0.0-obf.jar",
    "licensed-ScoreTpa-obf.jar",
    "ScoreAirdrop-1.0.0-obf.jar",
]

for jar in os.listdir(IN_DIR):
    src = os.path.join(IN_DIR, jar)
    dst = os.path.join(TMP_DIR, jar)
    needs_helpers = jar in JARS_WITH_GUARD
    with zipfile.ZipFile(src, "r") as zin:
        existing = set(zin.namelist())
        with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
            for it in zin.infolist():
                # Keep LicenseAPI.class as-is (already injected by rebuild_licenseapi.py upstream)
                zout.writestr(it, zin.read(it.filename), zipfile.ZIP_DEFLATED, compresslevel=9)
            if needs_helpers:
                added = 0
                for n, data in ALL_HELPERS.items():
                    if n in existing: continue
                    zout.writestr(n, data, zipfile.ZIP_DEFLATED, compresslevel=9)
                    added += 1
                print(f"{jar}: +{added} helper classes")
            else:
                print(f"{jar}: copied (no guard)")

print("\nDone -> obfuscated-final8b/")
