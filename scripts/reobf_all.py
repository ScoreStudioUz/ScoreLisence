"""Re-obfuscate all 6 mvn-rebuilt jars (5 plugins + ScoreLicense) via Skidfuscator
with safe config (no flowObfuscation) and write into obfuscated-final6/.

Required after any source change in:
  - licensed/ScoreLicense
  - licensed/ScoreAirDrop, ScoreAutoBuyer, ScoreHWAuction, ScorePvP, ScoreTpa

Run AFTER `mvn package` for each plugin. After this, run the normal pipeline:
  rename_main.py -> build_v2.py -> fix_helpers.py -> fix_manifest.py -> deliver_v2.py
"""
import os, subprocess, sys, shutil

ROOT = "C:/Users/shoxg/OneDrive/Desktop/plugins"
SKID = os.path.join(ROOT, "skidfuscator.jar")
LIBS = os.path.join(ROOT, "libs")
FINAL6 = os.path.join(ROOT, "obfuscated-final6")

JOBS = [
    # (work_subdir, src_jar, dst_jar_name, heap_xmx)
    # ScoreLicense — NOT reobfuscated here; uses target/ScoreLicense-1.0.0-obf.jar
    # directly (already ProGuard'd + Skidfuscator'd via maven pom). Copy below.
    ("tmp-skid-airdrop",
     "licensed/ScoreAirDrop/target/ScoreAirdrop-1.0.0-shaded.jar",
     "ScoreAirdrop-1.0.0-obf.jar",
     "4g"),
    ("tmp-skid-autobuyer",
     "licensed/ScoreAutoBuyer/target/ScoreAutoBuyer-1.0.0.jar",
     "licensed-ScoreAutoBuyer-1.0.0-obf.jar",
     "4g"),
    ("tmp-skid-hwauction",
     "licensed/ScoreHWAuction/target/ScoreHWAuction-1.0.0-shaded.jar",
     "licensed-ScoreHWAuction-obf.jar",
     "4g"),
    ("tmp-skid-pvp",
     "licensed/ScorePvP/target/ScorePvP-1.0.0-shaded.jar",
     "licensed-ScorePvP-1.0.0-obf.jar",
     "6g"),
    ("tmp-skid-tpa",
     "licensed/ScoreTpa/target/ScoreTpa-1.1.0.jar",
     "licensed-ScoreTpa-obf.jar",
     "4g"),
]

CFG_TEXT = """driver { phantom = true, fuckit = false }
skidfuscator { debug = false, notrack = true }
renamer { enabled = true, exclude = [] }
stringEncryption { enabled = true, type = "STANDARD" }
flowObfuscation { enabled = false }
numberObfuscation { enabled = false }
referenceObfuscation { enabled = false }
sdkInjection { enabled = false }
exemptions = []
"""

os.makedirs(FINAL6, exist_ok=True)

# ---- ScoreLicense: direct copy (NO Skidfuscator) ----
# target/ScoreLicense-1.0.0-obf.jar is the ProGuard+Skidfuscator output from
# maven (2.7MB). Re-running Skidfuscator on the shaded jar bloats it to 10.9MB.
lic_src = os.path.join(ROOT, "licensed/ScoreLicense/target/ScoreLicense-1.0.0-obf.jar")
lic_dst = os.path.join(FINAL6, "licensed-ScoreLicense-1.0.0-obf.jar")
if not os.path.exists(lic_src):
    print(f"!! missing {lic_src} — run `mvn package` in ScoreLicense first")
    sys.exit(1)
shutil.copy(lic_src, lic_dst)
print(f"=== licensed-ScoreLicense-1.0.0-obf.jar ===")
print(f"  copy: {lic_src}")
print(f"  ok: {os.path.getsize(lic_dst):,} bytes -> {lic_dst}")

failed = []
for work_sub, src_rel, dst_name, xmx in JOBS:
    src = os.path.join(ROOT, src_rel)
    if not os.path.exists(src):
        # try unshaded fallback
        alt = src.replace("-shaded.jar", ".jar")
        if os.path.exists(alt):
            src = alt
    if not os.path.exists(src):
        print(f"[skip] missing source: {src}")
        failed.append(dst_name)
        continue

    work = os.path.join(ROOT, work_sub)
    if os.path.exists(work):
        shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work)

    cfg = os.path.join(work, "skid.hocon")
    with open(cfg, "w", encoding="utf-8") as f:
        f.write(CFG_TEXT)

    in_jar = os.path.join(work, "in.jar")
    out_jar = os.path.join(work, "out.jar")
    shutil.copy(src, in_jar)

    print(f"\n=== {dst_name} ===")
    print(f"  src: {src}")
    r = subprocess.run(
        ["java", f"-Xmx{xmx}", "-jar", SKID, "obfuscate",
         "-cfg", cfg, "-li", LIBS, "-ph", "-o", out_jar, in_jar],
        capture_output=True, text=True, cwd=work,
    )
    if not os.path.exists(out_jar):
        print("STDOUT:", r.stdout[-2000:])
        print("STDERR:", r.stderr[-1500:])
        failed.append(dst_name)
        continue

    dst = os.path.join(FINAL6, dst_name)
    shutil.copy(out_jar, dst)
    print(f"  ok: {os.path.getsize(out_jar):,} bytes -> {dst}")

if failed:
    print(f"\n!! FAILED: {failed}")
    sys.exit(1)
print("\nAll jars reobfuscated into obfuscated-final6/.")
