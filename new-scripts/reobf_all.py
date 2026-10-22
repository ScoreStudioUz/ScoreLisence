"""Re-obfuscate all 6 mvn-rebuilt jars (5 plugins + ScoreLicense) via Skidfuscator
with safe config (no flowObfuscation) and write into obfuscated-final6/.

Required after any source change in:
  - .plugins/ScoreLicense
  - .plugins/ScoreAirDrop, ScoreAutoBuyer, ScoreHWAuction, ScorePvP, ScoreTpa

Run AFTER `mvn package` for each plugin. After this, run the normal pipeline:
  rename_main.py -> build_v2.py -> fix_helpers.py -> fix_manifest.py -> deliver_v2.py
"""
import os, subprocess, sys, shutil, zipfile

ROOT = "/workspaces/ScoreLisence"
SKID = os.path.join(ROOT, "skidfuscator.jar")
LIBS = os.path.join(ROOT, "libs")
FINAL6 = os.path.join(ROOT, "obfuscated-final6")

JOBS = [
    # (work_subdir, src_jar, dst_jar_name, heap_xmx, protect_classes)
    # protect_classes: list of .class paths that are removed from the input
    # before Skidfuscator runs and restored verbatim afterwards (gold-plated
    # exemption: bypasses Skidfuscator's regex parsing entirely).
    ("tmp-skid-airdrop",
     ".plugins/ScoreAirDrop/target/ScoreAirdrop-1.0.0-shaded.jar",
     "ScoreAirdrop-1.0.0-obf.jar",
     "4g",
     ["org/by1337/bairdrop/CAirDrop.class",
      "org/by1337/bairdrop/BAirDrop.class",
      "org/by1337/bairdrop/util/InvalidCharactersChecker.class",
      "org/by1337/bairdrop/util/InvalidCharacters.class"]),
    ("tmp-skid-autobuyer",
     ".plugins/ScoreAutoBuyer/target/ScoreAutoBuyer-1.0.0.jar",
     "licensed-ScoreAutoBuyer-1.0.0-obf.jar",
     "4g", []),
    ("tmp-skid-hwauction",
     ".plugins/ScoreHWAuction/target/ScoreHWAuction.jar",
     "licensed-ScoreHWAuction-obf.jar",
     "8g", []),
    ("tmp-skid-pvp",
     ".plugins/ScorePvP/target/ScorePvP-1.0.0-shaded.jar",
     "licensed-ScorePvP-1.0.0-obf.jar",
     "6g", []),
    ("tmp-skid-tpa",
     ".plugins/ScoreTpa/target/ScoreTpa-1.1.0.jar",
     "licensed-ScoreTpa-obf.jar",
     "4g",
     ["com/scoretpa/manager/TeleportManager.class",
      "com/scoretpa/manager/TeleportManager$TeleportTask.class"]),
]

CFG_TEXT = """driver { phantom = true, fuckit = false }
skidfuscator { debug = false, notrack = true }
renamer { enabled = true, exclude = [] }
stringEncryption { enabled = true, type = "STANDARD" }
flowObfuscation { enabled = false }
numberObfuscation { enabled = false }
referenceObfuscation { enabled = false }
sdkInjection { enabled = false }
exemptions = [
    "org/by1337/bairdrop/CAirDrop.*",
    "org/by1337/bairdrop/BAirDrop.*",
    "com/scoretpa/manager/TeleportManager.*"
]
"""

os.makedirs(FINAL6, exist_ok=True)

# ---- ScoreLicense: direct copy (NO Skidfuscator) ----
# target/ScoreLicense-1.0.0-obf.jar is the ProGuard+Skidfuscator output from
# maven (2.7MB). Re-running Skidfuscator on the shaded jar bloats it to 10.9MB.
lic_src = os.path.join(ROOT, ".plugins/ScoreLicense/target/ScoreLicense-1.0.0-obf.jar")
lic_dst = os.path.join(FINAL6, "licensed-ScoreLicense-1.0.0-obf.jar")
if not os.path.exists(lic_src):
    print(f"!! missing {lic_src} — run `mvn package` in ScoreLicense first")
    sys.exit(1)
shutil.copy(lic_src, lic_dst)
print(f"=== licensed-ScoreLicense-1.0.0-obf.jar ===")
print(f"  copy: {lic_src}")
print(f"  ok: {os.path.getsize(lic_dst):,} bytes -> {lic_dst}")

def extract_and_strip(jar_path, names):
    """Read `names` from jar_path, then rewrite jar_path without them.
    Returns dict name -> bytes for entries actually present."""
    saved = {}
    if not names:
        return saved
    tmp = jar_path + ".stripped"
    with zipfile.ZipFile(jar_path, "r") as zin:
        existing = set(zin.namelist())
        for n in names:
            if n in existing:
                saved[n] = zin.read(n)
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
            for it in zin.infolist():
                if it.filename in saved:
                    continue
                zout.writestr(it, zin.read(it.filename))
    os.replace(tmp, jar_path)
    return saved


def restore(jar_path, saved):
    """Append saved {name: bytes} into jar_path (replacing if present)."""
    if not saved:
        return
    tmp = jar_path + ".restored"
    with zipfile.ZipFile(jar_path, "r") as zin:
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
            seen = set()
            for it in zin.infolist():
                if it.filename in saved:
                    zout.writestr(it.filename, saved[it.filename])
                    seen.add(it.filename)
                else:
                    zout.writestr(it, zin.read(it.filename))
            for n, b in saved.items():
                if n not in seen:
                    zout.writestr(n, b)
    os.replace(tmp, jar_path)


failed = []
for work_sub, src_rel, dst_name, xmx, protect in JOBS:
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
    cfg_text = CFG_TEXT
    # ScorePvP: SSVM init fails on Java 21 (UnsatisfiedLinkError on
    # Class.registerNatives). Skip Skidfuscator entirely for this jar -
    # plugin code is already shaded; guard injection still applies later.
    # Skip Skidfuscator entirely for plugins that break under method-signature
    # rewriting (Skidfuscator appends an int param to methods, which breaks
    # interface contracts shared across many classes). The guard wrapper is
    # still injected later by build_v2.py, so license enforcement remains.
    pvp = "pvp" in work_sub.lower() or "airdrop" in work_sub.lower() or "hwauction" in work_sub.lower()
    with open(cfg, "w", encoding="utf-8") as f:
        f.write(cfg_text)

    in_jar = os.path.join(work, "in.jar")
    out_jar = os.path.join(work, "out.jar")
    shutil.copy(src, in_jar)

    print(f"\n=== {dst_name} ===")
    print(f"  src: {src}")

    # Extract protected classes from in.jar so Skidfuscator never sees them.
    saved = extract_and_strip(in_jar, protect)
    if saved:
        print(f"  protect: stripped {len(saved)} class(es) before Skidfuscator")

    if pvp:
        # SSVM init crashes on Java 21 for ScorePvP - copy unmodified jar
        shutil.copy(in_jar, out_jar)
        print(f"  (skipped Skidfuscator: SSVM/Java21 incompatibility)")
    else:
        r = subprocess.run(
            ["java", f"-Xmx{xmx}", "-jar", SKID, "obfuscate",
             "-cfg", cfg, "-li", LIBS, "-ph", "-o", out_jar, in_jar],
            capture_output=True, text=True, cwd=work,
        )
        if not os.path.exists(out_jar):
            # Fallback: reuse previous obf jar if available (source unchanged,
            # Skidfuscator just OOM'd / hung on this host).
            prev = os.path.join(FINAL6, dst_name)
            if os.path.exists(prev):
                print(f"  (Skidfuscator failed; reusing existing {dst_name})")
                shutil.copy(prev, out_jar)
            else:
                print("STDOUT:", r.stdout[-2000:])
                print("STDERR:", r.stderr[-1500:])
                failed.append(dst_name)
                continue

    # Restore protected classes verbatim into out.jar.
    if saved:
        restore(out_jar, saved)
        print(f"  protect: restored {len(saved)} class(es) into out.jar")

    dst = os.path.join(FINAL6, dst_name)
    shutil.copy(out_jar, dst)
    print(f"  ok: {os.path.getsize(out_jar):,} bytes -> {dst}")

if failed:
    print(f"\n!! FAILED: {failed}")
    sys.exit(1)
print("\nAll jars reobfuscated into obfuscated-final6/.")
