"""Build NoLic variants: obfuscated jars without guard wrapper / license check.
Source: obfuscated-final7/ (after rename_main, before build_v2 guard injection).
Output: obfuscated-final9/nolic-*.jar  (also delivered by deliver_v2.py)
"""
import os, shutil, zipfile, re

ROOT = "/workspaces/ScoreLisence"
SRC = os.path.join(ROOT, "obfuscated-final7")
DST = os.path.join(ROOT, "obfuscated-final9")
os.makedirs(DST, exist_ok=True)

# (input jar in final7, output nolic name, original-main-class to restore)
JOBS = [
    ("ScoreAirdrop-1.0.0-obf.jar",
     "nolic-ScoreAirdrop-1.0.0-obf.jar",
     "org.by1337.bairdrop.BAirDrop"),
    ("licensed-ScoreAutoBuyer-1.0.0-obf.jar",
     "nolic-ScoreAutoBuyer-1.0.0-obf.jar",
     None),
    ("licensed-ScoreHWAuction-obf.jar",
     "nolic-ScoreHWAuction-obf.jar",
     None),
    ("licensed-ScorePvP-1.0.0-obf.jar",
     "nolic-ScorePvP-1.0.0-obf.jar",
     None),
    ("licensed-ScoreTpa-obf.jar",
     "nolic-ScoreTpa-1.1.0-obf.jar",
     "com.scoretpa.ScoreTpa"),
]


def detect_main(jar_path):
    """Read plugin.yml from jar and return current main: value."""
    with zipfile.ZipFile(jar_path, "r") as z:
        try:
            yml = z.read("plugin.yml").decode("utf-8")
        except KeyError:
            return None
    m = re.search(r"^main:\s*(\S+)\s*$", yml, re.M)
    return m.group(1) if m else None


for src_name, dst_name, expected_main in JOBS:
    src = os.path.join(SRC, src_name)
    if not os.path.exists(src):
        print(f"[skip] missing {src}")
        continue

    current_main = detect_main(src)
    # In final7 the main: in plugin.yml still points to the original plugin
    # main class (guard injection happens later in build_v2). So no rewrite of
    # plugin.yml is needed — we just copy the jar verbatim.
    if expected_main and current_main and current_main != expected_main:
        # final7 plugin.yml should still hold the real main. If not, rewrite.
        tmp = src + ".fixmain"
        with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
            for it in zin.infolist():
                if it.filename == "plugin.yml":
                    yml = zin.read(it.filename).decode("utf-8")
                    yml = re.sub(r"^main:.*$", f"main: {expected_main}", yml, count=1, flags=re.M)
                    zout.writestr("plugin.yml", yml)
                else:
                    zout.writestr(it, zin.read(it.filename))
        shutil.move(tmp, os.path.join(DST, dst_name))
        print(f"  {dst_name}  (main rewritten: {current_main} -> {expected_main}, {os.path.getsize(os.path.join(DST, dst_name)):,} bytes)")
    else:
        shutil.copy(src, os.path.join(DST, dst_name))
        print(f"  {dst_name}  (main: {current_main}, {os.path.getsize(os.path.join(DST, dst_name)):,} bytes)")

print(f"\nDone -> {DST}")
