"""Re-obfuscate ScorePvP with safer Skidfuscator config (no flowObfuscation).
Reads: licensed/ScorePvP/target/ScorePvP-1.0.0-shaded.jar
Writes: obfuscated-final6/licensed-ScorePvP-1.0.0-obf.jar
"""
import os, subprocess, sys, shutil

ROOT = "C:/Users/shoxg/OneDrive/Desktop/plugins"
SKID = os.path.join(ROOT, "skidfuscator.jar")
LIBS = os.path.join(ROOT, "libs")
SRC = os.path.join(ROOT, "licensed/ScorePvP/target/ScorePvP-1.0.0-shaded.jar")
DST = os.path.join(ROOT, "obfuscated-final6/licensed-ScorePvP-1.0.0-obf.jar")
WORK = os.path.join(ROOT, "tmp-skid-pvp")

if not os.path.exists(SRC):
    SRC = os.path.join(ROOT, "licensed/ScorePvP/target/ScorePvP-1.0.0.jar")
assert os.path.exists(SRC), f"missing: {SRC}"
if os.path.exists(WORK): shutil.rmtree(WORK, ignore_errors=True)
os.makedirs(WORK)

cfg = os.path.join(WORK, "skid.hocon")
with open(cfg, "w", encoding="utf-8") as f:
    f.write("""driver { phantom = true, fuckit = false }
skidfuscator { debug = false, notrack = true }
renamer { enabled = true, exclude = [] }
stringEncryption { enabled = true, type = "STANDARD" }
flowObfuscation { enabled = false }
numberObfuscation { enabled = true }
referenceObfuscation { enabled = true }
sdkInjection { enabled = false }
exemptions = []
""")

in_jar = os.path.join(WORK, "in.jar")
out_jar = os.path.join(WORK, "out.jar")
shutil.copy(SRC, in_jar)
print(f"[skid] {in_jar} -> {out_jar}")
r = subprocess.run(
    ["java","-Xmx6g","-jar",SKID,"obfuscate","-cfg",cfg,"-li",LIBS,"-ph","-o",out_jar,in_jar],
    capture_output=True, text=True, cwd=WORK,
)
if not os.path.exists(out_jar):
    print(r.stdout[-3000:]); print(r.stderr[-1500:]); sys.exit(1)
print(f"[ok] {os.path.getsize(out_jar):,} bytes")
shutil.copy(out_jar, DST)
print(f"[copy] -> {DST}")
