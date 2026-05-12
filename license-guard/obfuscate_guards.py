"""Build a standalone guard-bundle jar, obfuscate via Skidfuscator,
extract obfuscated guard classes + LicenseAPI, reinject into final5 jars."""
import os, subprocess, shutil, zipfile, re, glob, sys

ROOT = "C:/Users/shoxg/OneDrive/Desktop/plugins"
GUARD = os.path.join(ROOT, "license-guard")
BUILD = os.path.join(GUARD, "build")  # already has compiled wrappers + license_api
WORK = os.path.join(GUARD, "skid-work")
SKIDFUSCATOR = os.path.join(ROOT, "skidfuscator.jar")
LIBS = os.path.join(ROOT, "libs")
PAPER = os.path.join(LIBS, "paper-api-1.21.11-R0.1-SNAPSHOT.jar")
IN_DIR = os.path.join(ROOT, "obfuscated-final5")
OUT_DIR = os.path.join(ROOT, "obfuscated-final6")

# Map plugin -> (jar_filename, wrapper_class_dotted, wrapper_arc_path)
PLUGINS = {
    "ScoreHWAuction": ("licensed-ScoreHWAuction-obf.jar", "ru.score.hwauction.guard.AuctionGuard"),
    "ScoreAutoBuyer": ("licensed-ScoreAutoBuyer-1.0.0-obf.jar", "com.scoreautobuyer.guard.AutoBuyerGuard"),
    "ScorePvP":       ("licensed-ScorePvP-1.0.0-obf.jar", "com.scorepvp.guard.PvPGuard"),
    "ScoreTpa":       ("licensed-ScoreTpa-obf.jar", "com.scoretpa.guard.TpaGuard"),
    "ScoreAirdrop":   ("ScoreAirdrop-1.0.0-obf.jar", "org.by1337.bairdrop.guard.AirdropGuard"),
}
LICENSE_JAR = "licensed-ScoreLicense-1.0.0-obf.jar"
LICENSE_API_ARC = "com/score/license/LicenseAPI.class"

if os.path.exists(WORK): shutil.rmtree(WORK)
os.makedirs(WORK)
os.makedirs(OUT_DIR, exist_ok=True)

# ---- 1) Assemble bundle.jar with all guards + LicenseAPI ----
print("[1] assembling bundle.jar...")
bundle_in = os.path.join(WORK, "bundle.jar")
api_class_path = os.path.join(BUILD, "license_api", LICENSE_API_ARC)
with zipfile.ZipFile(bundle_in, "w", zipfile.ZIP_DEFLATED) as z:
    # LicenseAPI
    with open(api_class_path, "rb") as f:
        z.writestr(LICENSE_API_ARC, f.read())
    # each guard
    for plug, (jar, wrapper) in PLUGINS.items():
        wrapper_arc = wrapper.replace(".", "/") + ".class"
        wrapper_file = os.path.join(BUILD, plug, "out", wrapper_arc)
        with open(wrapper_file, "rb") as f:
            z.writestr(wrapper_arc, f.read())
    # plugin.yml so Skidfuscator accepts it; pick AuctionGuard as fake main
    yml = "name: GuardBundle\nversion: 1.0.0\nmain: ru.score.hwauction.guard.AuctionGuard\napi-version: 1.20\n"
    z.writestr("plugin.yml", yml)
print(f"  -> {bundle_in}  ({os.path.getsize(bundle_in):,} bytes)")

# ---- 2) Skidfuscator config ----
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

# ---- 3) Run Skidfuscator ----
print("[2] running Skidfuscator on bundle...")
bundle_out = os.path.join(WORK, "bundle-out.jar")
cmd = [
    "java", "-jar", SKIDFUSCATOR,
    "obfuscate",
    "-cfg", cfg,
    "-li", LIBS,
    "-ph",
    "-o", bundle_out,
    bundle_in,
]
r = subprocess.run(cmd, capture_output=True, text=True, cwd=WORK)
if r.returncode != 0:
    print("STDOUT:", r.stdout[-3000:])
    print("STDERR:", r.stderr[-3000:])
    sys.exit(1)
# locate output
if not os.path.exists(bundle_out):
    # Skidfuscator default naming
    cand = glob.glob(os.path.join(WORK, "*-out.jar"))
    if cand:
        bundle_out = cand[0]
    else:
        print("Output jar not found. Skidfuscator stdout tail:")
        print(r.stdout[-2000:])
        sys.exit(1)
print(f"  -> {bundle_out}  ({os.path.getsize(bundle_out):,} bytes)")

# ---- 4) Read obfuscated classes back ----
print("[3] extracting obfuscated guard classes...")
obf_classes = {}
with zipfile.ZipFile(bundle_out, "r") as z:
    for n in z.namelist():
        if n.endswith(".class") and not n.startswith("META-INF/"):
            obf_classes[n] = z.read(n)
            if "guard" in n or "LicenseAPI" in n:
                print(f"  {n}: {len(obf_classes[n])} bytes")

# ---- 5) Inject obfuscated guards back into matching jars ----
# We must replace original wrapper class + add any *new* helper classes Skidfuscator created
# inside the SAME package (e.g. for string-decrypt helpers). Strategy:
# - For each plugin's jar: replace its wrapper, also copy ALL classes whose package starts
#   with the wrapper's package prefix (e.g. ru/score/hwauction/guard/) from obf bundle.
print("[4] injecting into final6 jars...")

# Group obf classes by plugin via their package prefix
def pkg_prefix(wrapper_dotted):
    return wrapper_dotted.rsplit(".", 1)[0].replace(".", "/") + "/"

for plug, (jar_name, wrapper_dotted) in PLUGINS.items():
    src = os.path.join(IN_DIR, jar_name)
    dst = os.path.join(OUT_DIR, jar_name)
    prefix = pkg_prefix(wrapper_dotted)
    relevant = {n: data for n, data in obf_classes.items() if n.startswith(prefix)}
    print(f"  {plug}: {len(relevant)} classes under {prefix}")

    with zipfile.ZipFile(src, "r") as zin:
        with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
            for it in zin.infolist():
                # drop existing files in guard/ prefix (we replace)
                if it.filename.startswith(prefix):
                    continue
                zout.writestr(it, zin.read(it.filename), zipfile.ZIP_DEFLATED, compresslevel=9)
            for n, data in relevant.items():
                zout.writestr(n, data, zipfile.ZIP_DEFLATED, compresslevel=9)
    print(f"  -> {dst}  ({os.path.getsize(dst):,} bytes)")

# ---- 6) Inject obfuscated LicenseAPI + companions back into ScoreLicense jar ----
print("[5] injecting obfuscated LicenseAPI into ScoreLicense jar...")
lic_src = os.path.join(IN_DIR, LICENSE_JAR)
lic_dst = os.path.join(OUT_DIR, LICENSE_JAR)
api_relevant = {n: d for n, d in obf_classes.items() if n.startswith("com/score/license/")}
# But com/score/license/ has TONS of unrelated existing classes; we only want LicenseAPI.class
# (Skidfuscator may have added helper classes for string decrypt at top-level or in synthetic pkg)
# Find all classes that LicenseAPI references via constant pool? Simpler: only replace LicenseAPI itself,
# plus any classes that are new (i.e. not in original ScoreLicense jar).
with zipfile.ZipFile(lic_src, "r") as zin:
    existing_lic = set(zin.namelist())
    with zipfile.ZipFile(lic_dst, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
        for it in zin.infolist():
            if it.filename == LICENSE_API_ARC:
                continue  # replace
            zout.writestr(it, zin.read(it.filename), zipfile.ZIP_DEFLATED, compresslevel=9)
        # write obfuscated LicenseAPI
        if LICENSE_API_ARC in obf_classes:
            zout.writestr(LICENSE_API_ARC, obf_classes[LICENSE_API_ARC],
                          zipfile.ZIP_DEFLATED, compresslevel=9)
            print(f"  injected {LICENSE_API_ARC}")
        # Skidfuscator may have moved/added helpers; copy any class not already present
        for n, data in obf_classes.items():
            if n in existing_lic or n == LICENSE_API_ARC:
                continue
            # only copy classes that look related (com/score/license/* OR root-level skid helpers)
            if n.startswith("com/score/license/") or "/" not in n.rstrip(".class"):
                zout.writestr(n, data, zipfile.ZIP_DEFLATED, compresslevel=9)
                print(f"  injected helper {n}")
print(f"  -> {lic_dst}  ({os.path.getsize(lic_dst):,} bytes)")

# ---- 7) Copy through unmodified jars (nolic + others) ----
print("[6] copying remaining unmodified jars...")
copied = set()
for jar in os.listdir(IN_DIR):
    out = os.path.join(OUT_DIR, jar)
    if os.path.exists(out):
        continue
    shutil.copy(os.path.join(IN_DIR, jar), out)
    print(f"  {jar}")

print("\nDone.")
