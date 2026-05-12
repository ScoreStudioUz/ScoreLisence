"""Build LicenseAPI + per-plugin wrapper, inject into each JAR, update plugin.yml."""
import os, subprocess, shutil, zipfile, re, hashlib, struct

ROOT = "C:/Users/shoxg/OneDrive/Desktop/plugins"
GUARD = os.path.join(ROOT, "license-guard")
SRC_BASE = os.path.join(GUARD, "src")
BUILD = os.path.join(GUARD, "build")
TEMPLATE = os.path.join(GUARD, "wrapper_template.java")
IN_DIR = os.path.join(ROOT, "obfuscated-final3")
OUT_DIR = os.path.join(ROOT, "obfuscated-final4")
LIBS = os.path.join(ROOT, "libs")
PAPER = os.path.join(LIBS, "paper-api-1.21.11-R0.1-SNAPSHOT.jar")

# expected identity hex (matches LicenseAPI.identity())
M = [8398573456194776669, 8269354300280025007, 2206680772570605703, -1104072232727625432]
X = [6130692733054075381, -1934450547547622840, -2764922105262081080, 2317992633624059855]
def s64(v): return v if v < (1<<63) else v - (1<<64)
def to_u(v): return v & 0xFFFFFFFFFFFFFFFF
EXPECTED_ID = format((to_u(X[0]) ^ to_u(M[0])) ^ (to_u(X[3]) ^ to_u(M[3])), "x")
print("Expected ID:", EXPECTED_ID)

# plugin -> (jar_filename, real_main_class, wrapper_pkg, wrapper_simple_name)
PLUGINS = {
    "ScoreHWAuction": ("licensed-ScoreHWAuction-obf.jar", "ru.score.hwauction.ScoreHWAuction", "ru.score.hwauction.guard", "AuctionGuard"),
    "ScoreAutoBuyer": ("licensed-ScoreAutoBuyer-1.0.0-obf.jar", "com.scoreautobuyer.ScoreAutoBuyer", "com.scoreautobuyer.guard", "AutoBuyerGuard"),
    "ScorePvP":       ("licensed-ScorePvP-1.0.0-obf.jar", "com.scorepvp.ScorePvP", "com.scorepvp.guard", "PvPGuard"),
    "ScoreTpa":       ("licensed-ScoreTpa-obf.jar", "com.scoretpa.ScoreTpa", "com.scoretpa.guard", "TpaGuard"),
    "ScoreAirdrop":   ("ScoreAirdrop-1.0.0-obf.jar", "org.by1337.bairdrop.BAirDrop", "org.by1337.bairdrop.guard", "AirdropGuard"),
}

LICENSE_JAR = "licensed-ScoreLicense-1.0.0-obf.jar"

if os.path.exists(BUILD): shutil.rmtree(BUILD)
os.makedirs(BUILD)
os.makedirs(OUT_DIR, exist_ok=True)

# --- Step 1: compile LicenseAPI ---
print("\n[1] compiling LicenseAPI...")
api_build = os.path.join(BUILD, "license_api")
os.makedirs(api_build)
api_src = os.path.join(SRC_BASE, "com/score/license/LicenseAPI.java")
r = subprocess.run(["javac","-d",api_build,"-encoding","UTF-8","-source","8","-target","8", api_src],
                   capture_output=True, text=True)
if r.returncode: print(r.stderr); raise SystemExit(1)
print("  OK:", os.path.exists(os.path.join(api_build, "com/score/license/LicenseAPI.class")))

# --- Step 2: compile each wrapper ---
print("\n[2] compiling per-plugin wrappers...")
with open(TEMPLATE, "r", encoding="utf-8") as f:
    tmpl = f.read()

wrapper_classes = {}  # plugin -> (build_dir, main_dotted)
for plug, (jar, real_main, pkg, name) in PLUGINS.items():
    src_dir = os.path.join(BUILD, plug, "src", *pkg.split("."))
    os.makedirs(src_dir, exist_ok=True)
    src = (tmpl
           .replace("__PKG__", pkg)
           .replace("__NAME__", name)
           .replace("__REAL_MAIN__", real_main)
           .replace("__EXPECTED_ID__", EXPECTED_ID))
    src_file = os.path.join(src_dir, name + ".java")
    with open(src_file, "w", encoding="utf-8") as f:
        f.write(src)

    out_dir = os.path.join(BUILD, plug, "out")
    os.makedirs(out_dir, exist_ok=True)
    r = subprocess.run(["javac","-d",out_dir,"-encoding","UTF-8","-source","8","-target","8",
                        "-cp", PAPER, src_file], capture_output=True, text=True)
    if r.returncode:
        print(f"  {plug} FAIL:", r.stderr[:1500]); raise SystemExit(1)
    wrapper_classes[plug] = (out_dir, f"{pkg}.{name}")
    print(f"  {plug}: {pkg}.{name}")

# --- Step 3: inject LicenseAPI into ScoreLicense JAR ---
print("\n[3] injecting LicenseAPI into ScoreLicense JAR...")
src_jar = os.path.join(IN_DIR, LICENSE_JAR)
out_jar = os.path.join(OUT_DIR, LICENSE_JAR)
with zipfile.ZipFile(src_jar, "r") as zin:
    with zipfile.ZipFile(out_jar, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
        for item in zin.infolist():
            if item.filename == "com/score/license/LicenseAPI.class":
                continue  # drop decoy collision; we inject the real one below
            zout.writestr(item, zin.read(item.filename), zipfile.ZIP_DEFLATED, compresslevel=9)
        api_class = os.path.join(api_build, "com/score/license/LicenseAPI.class")
        with open(api_class, "rb") as f: data = f.read()
        zout.writestr("com/score/license/LicenseAPI.class", data, zipfile.ZIP_DEFLATED, compresslevel=9)
print(f"  -> {out_jar}")

# --- Step 4: inject wrapper + patch plugin.yml main: in each dependent JAR ---
print("\n[4] patching dependent JARs...")
for plug, (jar_name, real_main, pkg, name) in PLUGINS.items():
    src = os.path.join(IN_DIR, jar_name)
    dst = os.path.join(OUT_DIR, jar_name)
    out_dir, wrapper_dotted = wrapper_classes[plug]
    wrapper_arc = wrapper_dotted.replace(".", "/") + ".class"
    wrapper_file = os.path.join(out_dir, wrapper_arc)
    with open(wrapper_file, "rb") as f:
        wrapper_data = f.read()

    with zipfile.ZipFile(src, "r") as zin:
        with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
            for item in zin.infolist():
                if item.filename == wrapper_arc:
                    continue  # drop any colliding decoy
                if item.filename == "plugin.yml":
                    yml = zin.read(item.filename).decode("utf-8")
                    new_yml = re.sub(r"^main:.*$", f"main: {wrapper_dotted}", yml, count=1, flags=re.M)
                    zout.writestr("plugin.yml", new_yml, zipfile.ZIP_DEFLATED, compresslevel=9)
                else:
                    zout.writestr(item, zin.read(item.filename), zipfile.ZIP_DEFLATED, compresslevel=9)
            zout.writestr(wrapper_arc, wrapper_data, zipfile.ZIP_DEFLATED, compresslevel=9)
    sz = os.path.getsize(dst)
    print(f"  {jar_name}: main -> {wrapper_dotted}  ({sz:,} bytes)")

# --- Step 5: copy nolic-* through unchanged ---
print("\n[5] copying nolic-* JARs unchanged...")
for jar in os.listdir(IN_DIR):
    if jar.startswith("nolic-") and jar.endswith(".jar"):
        shutil.copy(os.path.join(IN_DIR, jar), os.path.join(OUT_DIR, jar))
        print(f"  {jar}")

print("\nDone.")
