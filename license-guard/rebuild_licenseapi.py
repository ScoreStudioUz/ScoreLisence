"""Recompile LicenseAPI.java (with setLicensed methods), obfuscate via Skidfuscator,
inject into ScoreLicense jar, recompute hashes for guards."""
import os, subprocess, sys, shutil, zipfile, hashlib

ROOT = "C:/Users/shoxg/OneDrive/Desktop/plugins"
GUARD = os.path.join(ROOT, "license-guard")
SRC = os.path.join(GUARD, "src")
WORK = os.path.join(GUARD, "api-work")
SKID = os.path.join(ROOT, "skidfuscator.jar")
LIBS = os.path.join(ROOT, "libs")
TARGET_JAR = os.path.join(ROOT, "obfuscated-final7/licensed-ScoreLicense-1.0.0-obf.jar")

if os.path.exists(WORK): shutil.rmtree(WORK, ignore_errors=True)
os.makedirs(WORK)

# 1. compile LicenseAPI
out = os.path.join(WORK, "out")
os.makedirs(out)
api_src = os.path.join(SRC, "com/score/license/LicenseAPI.java")
r = subprocess.run(["javac","-d",out,"-encoding","UTF-8","-source","8","-target","8",api_src],
                   capture_output=True, text=True)
if r.returncode: print(r.stderr); sys.exit(1)
print("compiled LicenseAPI.class")

# 2. bundle into jar with plugin.yml so Skidfuscator accepts it
bundle = os.path.join(WORK, "api-bundle.jar")
with zipfile.ZipFile(bundle, "w", zipfile.ZIP_DEFLATED) as z:
    api_class = os.path.join(out, "com/score/license/LicenseAPI.class")
    with open(api_class, "rb") as f:
        z.writestr("com/score/license/LicenseAPI.class", f.read())
    z.writestr("plugin.yml", "name: LicAPI\nversion: 1.0.0\nmain: com.score.license.LicenseAPI\napi-version: 1.20\n")

# 3. Skidfuscator
cfg = os.path.join(WORK, "skid.hocon")
with open(cfg, "w") as f:
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
    enabled = true
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
bundle_out = os.path.join(WORK, "api-bundle-out.jar")
r = subprocess.run(["java","-jar",SKID,"obfuscate","-cfg",cfg,"-li",LIBS,"-ph","-o",bundle_out,bundle],
                   capture_output=True, text=True, cwd=WORK)
if not os.path.exists(bundle_out):
    print("STDOUT:", r.stdout[-3000:]); print("STDERR:", r.stderr[-3000:]); sys.exit(1)
print(f"obfuscated bundle: {os.path.getsize(bundle_out):,} bytes")

# 4. Extract obfuscated classes
obf = {}
with zipfile.ZipFile(bundle_out, "r") as z:
    for n in z.namelist():
        if n.endswith(".class") and not n.startswith("META-INF/"):
            obf[n] = z.read(n)
print(f"got {len(obf)} obf classes:")
for n in obf: print(" ", n, len(obf[n]), "bytes")

# 5. Inject into ScoreLicense jar (replace LicenseAPI.class + add helper(s))
NEW_API = obf.get("com/score/license/LicenseAPI.class")
HELPERS = {n: d for n, d in obf.items() if n != "com/score/license/LicenseAPI.class"}

tmp = TARGET_JAR + ".tmp"
with zipfile.ZipFile(TARGET_JAR, "r") as zin:
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
        existing = set(zin.namelist())
        for it in zin.infolist():
            if it.filename == "com/score/license/LicenseAPI.class":
                zout.writestr(it, NEW_API, zipfile.ZIP_DEFLATED, compresslevel=9)
            else:
                zout.writestr(it, zin.read(it.filename), zipfile.ZIP_DEFLATED, compresslevel=9)
        for n, d in HELPERS.items():
            if n not in existing:
                zout.writestr(n, d, zipfile.ZIP_DEFLATED, compresslevel=9)
                print(f"  +helper {n}")
shutil.move(tmp, TARGET_JAR)
print(f"\npatched {TARGET_JAR}")

# Same for ScorePvP, ScoreTpa, ScoreHWAuction, ScoreAutoBuyer, ScoreAirdrop (need helper for cross-jar hash)
# Actually no — those don't contain LicenseAPI.class. They only need helpers if Skidfuscator embedded
# string-decrypt calls in their guard. Helpers were already injected by fix_helpers.py.
# But the new LicenseAPI may need a NEW helper that's not in those jars yet.
DEPS = [
    "licensed-ScoreHWAuction-obf.jar",
    "licensed-ScoreAutoBuyer-1.0.0-obf.jar",
    "licensed-ScorePvP-1.0.0-obf.jar",
    "licensed-ScoreTpa-obf.jar",
    "ScoreAirdrop-1.0.0-obf.jar",
]
for jar in DEPS:
    p = os.path.join(ROOT, "obfuscated-final7", jar)
    tmp = p + ".tmp"
    with zipfile.ZipFile(p, "r") as zin:
        existing = set(zin.namelist())
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
            for it in zin.infolist():
                zout.writestr(it, zin.read(it.filename), zipfile.ZIP_DEFLATED, compresslevel=9)
            for n, d in HELPERS.items():
                if n not in existing:
                    zout.writestr(n, d, zipfile.ZIP_DEFLATED, compresslevel=9)
    shutil.move(tmp, p)
    print(f"  patched {jar} (added helpers)")

# 6. Print new hashes for guards
print("\n=== NEW HASHES ===")
classes = [
    "com/score/license/LicenseDatabase.class",
    "com/score/license/LicenseStorage.class",
    "com/score/license/LicenseHeartbeat.class",
    "com/score/license/LicenseAPI.class",
    "com/score/license/lIIIll1O0OOIl0IOOl.class",
]
with zipfile.ZipFile(TARGET_JAR) as z:
    for c in classes:
        h = hashlib.sha256(z.read(c)).hexdigest()
        print(f'        {{"{c}", "{h}"}},')

# 7. Auto-update guard sources with new LicenseAPI hash
import re
new_api_hash = hashlib.sha256(NEW_API).hexdigest()
print(f"\nNew LicenseAPI hash: {new_api_hash}")

guard_files = [
    os.path.join(GUARD, "wrapper_template_v2.java"),
    os.path.join(GUARD, "src2/com/score/license/guard/LicenseGate.java"),
]
for gf in guard_files:
    if not os.path.exists(gf): continue
    with open(gf) as f: t = f.read()
    t2 = re.sub(r'"[0-9a-f]{64}"(\s*[,)};]?\s*//.*LicenseAPI|.*LicenseAPI\.class.*?"[0-9a-f]{64}")', "", t, count=0)
    # simpler: replace any 64-hex right after LicenseAPI.class
    t2 = re.sub(r'(LicenseAPI\.class[",\s.put(]*"\s*,?\s*\n?\s*")[0-9a-f]{64}',
                lambda m: m.group(1) + new_api_hash, t)
    if t2 != t:
        with open(gf, "w") as f: f.write(t2)
        print(f"  updated {os.path.basename(gf)}")
