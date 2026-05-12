"""Re-obfuscate ScoreTpa with safer Skidfuscator config (no flowObfuscation)
to fix ClassFormatError 'Illegal exception table range' in TeleportManager.

Reads: licensed/ScoreTpa/target/ScoreTpa License.jar  (clean mvn build)
Writes: obfuscated-final6/licensed-ScoreTpa-obf.jar   (replaces existing)

After running this, re-execute the normal pipeline:
  rename_main.py  (final6 -> final7) - actually rename_main only touches ScoreLicense entry,
                                       safe to re-run; copies through other jars unchanged.
  build_v2.py     (final7 -> final8)
  fix_helpers.py  (final8 -> final8b)
  fix_manifest.py (final8b -> final9)
  deliver_v2.py   (final9 -> ScorePlugins/)
"""
import os, subprocess, sys, shutil

ROOT = "C:/Users/shoxg/OneDrive/Desktop/plugins"
SKID = os.path.join(ROOT, "skidfuscator.jar")
LIBS = os.path.join(ROOT, "libs")
SRC = os.path.join(ROOT, "licensed/ScoreTpa/target/ScoreTpa License.jar")
DST = os.path.join(ROOT, "obfuscated-final6/licensed-ScoreTpa-obf.jar")
WORK = os.path.join(ROOT, "tmp-skid-tpa")

assert os.path.exists(SRC), f"missing: {SRC}"
if os.path.exists(WORK): shutil.rmtree(WORK, ignore_errors=True)
os.makedirs(WORK)

# Safer config: keep string + reference + number obfuscation + renamer,
# DISABLE flowObfuscation (this is what broke TeleportManager).
cfg = os.path.join(WORK, "skid.hocon")
with open(cfg, "w", encoding="utf-8") as f:
    f.write("""driver {
    phantom = true
    fuckit = false
}

skidfuscator {
    debug = false
    notrack = true
}

renamer {
    enabled = true
    exclude = []
}

stringEncryption {
    enabled = true
    type = "STANDARD"
}

flowObfuscation {
    enabled = false
}

numberObfuscation {
    enabled = true
}

referenceObfuscation {
    enabled = true
}

sdkInjection {
    enabled = false
}

exemptions = []
""")

in_jar = os.path.join(WORK, "in.jar")
out_jar = os.path.join(WORK, "out.jar")
shutil.copy(SRC, in_jar)

print(f"[skid] {in_jar}  ->  {out_jar}")
r = subprocess.run(
    ["java", "-jar", SKID, "obfuscate", "-cfg", cfg, "-li", LIBS, "-ph", "-o", out_jar, in_jar],
    capture_output=True, text=True, cwd=WORK,
)
if not os.path.exists(out_jar):
    print(r.stdout[-3000:])
    print(r.stderr[-1500:])
    sys.exit(1)

print(f"[ok] {os.path.getsize(out_jar):,} bytes")
shutil.copy(out_jar, DST)
print(f"[copy] -> {DST}")
