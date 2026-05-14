"""End-to-end: build LicenseGate (A self-hash) for ScoreLicense + new wrappers (B cross-jar hash) for dependents.
Then run Skidfuscator over the whole bundle, inject obfuscated classes back, write final8/."""
import os, subprocess, shutil, zipfile, re, sys, glob, hashlib

ROOT = "/workspaces/ScoreLisence"
GUARD = os.path.join(ROOT, "license-guard")
SRC2 = os.path.join(GUARD, "src2")
TPL2 = os.path.join(GUARD, "wrapper_template_v2.java")
WORK = os.path.join(GUARD, "skid-work2")
SKIDFUSCATOR = os.path.join(ROOT, "skidfuscator.jar")
LIBS = os.path.join(ROOT, "libs")
PAPER = os.path.join(LIBS, "paper-api-1.21.11-R0.1-SNAPSHOT.jar") + os.pathsep + os.path.join(LIBS, "adventure-key-4.26.1.jar")
IN_DIR = os.path.join(ROOT, "obfuscated-final7")
OUT_DIR = os.path.join(ROOT, "obfuscated-final8")

if os.path.exists(WORK): shutil.rmtree(WORK, ignore_errors=True)
os.makedirs(WORK)
os.makedirs(OUT_DIR, exist_ok=True)

EXPECTED_ID = "f11f84a33685f14f"
NEW_MAIN = "lIIIll1O0OOIl0IOOl"

LICENSE_JAR = "licensed-ScoreLicense-1.0.0-obf.jar"
LICENSE_GATE = "com.score.license.guard.LicenseGate"

PLUGINS = {
    "ScoreHWAuction": ("licensed-ScoreHWAuction-obf.jar", "ru.score.hwauction.ScoreHWAuction", "ru.score.hwauction.guard", "AuctionGuard"),
    "ScoreAutoBuyer": ("licensed-ScoreAutoBuyer-1.0.0-obf.jar", "com.scoreautobuyer.ScoreAutoBuyer", "com.scoreautobuyer.guard", "AutoBuyerGuard"),
    "ScorePvP":       ("licensed-ScorePvP-1.0.0-obf.jar", "com.scorepvp.ScorePvP", "com.scorepvp.guard", "PvPGuard"),
    "ScoreTpa":       ("licensed-ScoreTpa-obf.jar", "com.scoretpa.ScoreTpa", "com.scoretpa.guard", "TpaGuard"),
    "ScoreAirdrop":   ("ScoreAirdrop-1.0.0-obf.jar", "org.by1337.bairdrop.BAirDrop", "org.by1337.bairdrop.guard", "AirdropGuard"),
}

# ---- Step 1: compile LicenseGate + IntegrityChecker ----
print("[1] compile LicenseGate + IntegrityChecker...")
gate_out = os.path.join(WORK, "gate")
os.makedirs(gate_out)
gate_files = [
    os.path.join(SRC2, "com/score/license/guard/IntegrityChecker.java"),
    os.path.join(SRC2, "com/score/license/guard/LicenseGate.java"),
]
r = subprocess.run(["javac","-d",gate_out,"-encoding","UTF-8","-source","8","-target","8",
                    "-cp", PAPER] + gate_files, capture_output=True, text=True)
if r.returncode: print(r.stderr); sys.exit(1)
print("  OK")

# ---- Step 2: compile each plugin wrapper from v2 template ----
print("[2] compile per-plugin wrappers (v2 with cross-jar hash)...")
with open(TPL2, "r", encoding="utf-8") as f:
    tmpl = f.read()
wrap_out = os.path.join(WORK, "wrappers")
os.makedirs(wrap_out)
wrapper_classes = {}
for plug, (jar, real_main, pkg, name) in PLUGINS.items():
    src_dir = os.path.join(WORK, "wrap-src", *pkg.split("."))
    os.makedirs(src_dir, exist_ok=True)
    src = (tmpl
           .replace("__PKG__", pkg)
           .replace("__NAME__", name)
           .replace("__REAL_MAIN__", real_main)
           .replace("__EXPECTED_ID__", EXPECTED_ID))
    src_file = os.path.join(src_dir, name + ".java")
    with open(src_file, "w", encoding="utf-8") as f:
        f.write(src)
    r = subprocess.run(["javac","-d",wrap_out,"-encoding","UTF-8","-source","8","-target","8",
                        "-cp",PAPER,src_file], capture_output=True, text=True)
    if r.returncode: print(plug, r.stderr); sys.exit(1)
    wrapper_classes[plug] = f"{pkg}.{name}"
    print(f"  {plug}: {pkg}.{name}")

# ---- Step 3: assemble bundle.jar with everything ----
print("[3] bundle.jar...")
bundle_in = os.path.join(WORK, "bundle.jar")
with zipfile.ZipFile(bundle_in, "w", zipfile.ZIP_DEFLATED) as z:
    # gate classes
    for root,_,files in os.walk(gate_out):
        for f in files:
            if f.endswith(".class"):
                full = os.path.join(root,f)
                arc = os.path.relpath(full, gate_out).replace("\\","/")
                with open(full,"rb") as fp: z.writestr(arc, fp.read())
    # wrappers
    for root,_,files in os.walk(wrap_out):
        for f in files:
            if f.endswith(".class"):
                full = os.path.join(root,f)
                arc = os.path.relpath(full, wrap_out).replace("\\","/")
                with open(full,"rb") as fp: z.writestr(arc, fp.read())
    # need plugin.yml for Skidfuscator
    z.writestr("plugin.yml", "name: GuardBundle\nversion: 1.0.0\nmain: com.score.license.guard.LicenseGate\napi-version: 1.20\n")
print(f"  -> {bundle_in}  ({os.path.getsize(bundle_in):,} bytes)")

# ---- Step 4: Skidfuscator over bundle ----
print("[4] Skidfuscator...")
cfg = os.path.join(WORK, "skid.hocon")
with open(cfg,"w",encoding="utf-8") as f:
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

bundle_out = os.path.join(WORK, "bundle-out.jar")
r = subprocess.run(["java","-jar",SKIDFUSCATOR,"obfuscate","-cfg",cfg,"-li",LIBS,"-ph","-o",bundle_out,bundle_in],
                   capture_output=True, text=True, cwd=WORK)
if not os.path.exists(bundle_out):
    print(r.stdout[-2000:]); print(r.stderr[-1500:]); sys.exit(1)
print(f"  -> {bundle_out}  ({os.path.getsize(bundle_out):,} bytes)")

# ---- Step 5: extract obfuscated classes ----
print("[5] extract obf classes...")
obf = {}
with zipfile.ZipFile(bundle_out, "r") as z:
    for n in z.namelist():
        if n.endswith(".class") and not n.startswith("META-INF/"):
            obf[n] = z.read(n)
print(f"  {len(obf)} classes")

# ---- Step 6: ScoreLicense -> inject LicenseGate, IntegrityChecker, patch plugin.yml ----
print("[6] patch ScoreLicense jar with LicenseGate...")
src_lic = os.path.join(IN_DIR, LICENSE_JAR)
dst_lic = os.path.join(OUT_DIR, LICENSE_JAR)
gate_pkg_prefix = "com/score/license/guard/"
with zipfile.ZipFile(src_lic, "r") as zin:
    with zipfile.ZipFile(dst_lic, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
        for it in zin.infolist():
            if it.filename.startswith(gate_pkg_prefix):
                continue
            if it.filename == "plugin.yml":
                yml = zin.read(it.filename).decode("utf-8")
                yml = re.sub(r"^main:.*$", f"main: {LICENSE_GATE}", yml, count=1, flags=re.M)
                zout.writestr("plugin.yml", yml, zipfile.ZIP_DEFLATED, compresslevel=9)
            else:
                zout.writestr(it, zin.read(it.filename), zipfile.ZIP_DEFLATED, compresslevel=9)
        # add all obfuscated classes under com/score/license/guard/
        for n, data in obf.items():
            if n.startswith(gate_pkg_prefix):
                zout.writestr(n, data, zipfile.ZIP_DEFLATED, compresslevel=9)
print(f"  -> {dst_lic} ({os.path.getsize(dst_lic):,} bytes)")

# ---- Step 7: dependents -> replace guard with v2 obfuscated ----
print("[7] patch dependent jars with v2 wrappers...")
for plug, (jar, real_main, pkg, name) in PLUGINS.items():
    pkg_prefix = pkg.replace(".", "/") + "/"
    guard_main = f"{pkg}.{name}"
    src = os.path.join(IN_DIR, jar)
    dst = os.path.join(OUT_DIR, jar)
    relevant = {n: d for n, d in obf.items() if n.startswith(pkg_prefix)}
    with zipfile.ZipFile(src, "r") as zin:
        with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
            for it in zin.infolist():
                if it.filename.startswith(pkg_prefix):
                    continue
                if it.filename == "plugin.yml":
                    yml = zin.read(it.filename).decode("utf-8")
                    yml = re.sub(r"^main:.*$", f"main: {guard_main}", yml, count=1, flags=re.M)
                    zout.writestr("plugin.yml", yml, zipfile.ZIP_DEFLATED, compresslevel=9)
                else:
                    zout.writestr(it, zin.read(it.filename), zipfile.ZIP_DEFLATED, compresslevel=9)
            for n, data in relevant.items():
                zout.writestr(n, data, zipfile.ZIP_DEFLATED, compresslevel=9)
    print(f"  {jar}: main->{guard_main}, +{len(relevant)} guard classes  ({os.path.getsize(dst):,} bytes)")

# ---- Step 8: copy nolic-* unchanged ----
print("[8] copy unchanged jars...")
for jar in os.listdir(IN_DIR):
    out = os.path.join(OUT_DIR, jar)
    if os.path.exists(out): continue
    shutil.copy(os.path.join(IN_DIR, jar), out)
    print(f"  {jar}")

print("\nDone.")
