"""Step C: rename ScoreLicensePlugin -> lIIIll1O0OOIl0IOOl across all jars.
Both names are 18 chars so we can do byte-level replacement in:
- the .class file at com/score/license/ScoreLicensePlugin.class (zip entry name change)
- inside every .class constant pool (UTF8 entries) and inner_class refs
- plugin.yml main: field
- guard wrappers in dependent jars (REAL_MAIN constant) - same length-safe replace

After this step the main entry point class is no longer obviously named.
"""
import os, zipfile, shutil, re

OLD = "ScoreLicensePlugin"
NEW = "lIIIll1O0OOIl0IOOl"
assert len(OLD) == len(NEW), "names must be same length for byte-safe replace"

OLD_B = OLD.encode("ascii")
NEW_B = NEW.encode("ascii")

SRC = "/workspaces/ScoreLisence/obfuscated-final6"
OUT = "/workspaces/ScoreLisence/obfuscated-final7"
os.makedirs(OUT, exist_ok=True)

# Old plugin.yml uses a fully-qualified path
OLD_FQ = "com.score.license.ScoreLicensePlugin"
NEW_FQ = "com.score.license." + NEW

# Old class arc path
OLD_ARC = "com/score/license/ScoreLicensePlugin.class"
NEW_ARC = "com/score/license/" + NEW + ".class"

def patch_bytes(data: bytes) -> bytes:
    return data.replace(OLD_B, NEW_B)

def patch_yml(yml: bytes) -> bytes:
    s = yml.decode("utf-8")
    s = s.replace(OLD_FQ, NEW_FQ)
    s = s.replace(OLD, NEW)  # safety
    return s.encode("utf-8")

for jar in sorted(os.listdir(SRC)):
    if not jar.endswith(".jar"): continue
    src_p = os.path.join(SRC, jar)
    dst_p = os.path.join(OUT, jar)
    n_class = 0
    with zipfile.ZipFile(src_p, "r") as zin:
        with zipfile.ZipFile(dst_p, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
            for it in zin.infolist():
                data = zin.read(it.filename)
                fname = it.filename
                if fname == OLD_ARC:
                    fname = NEW_ARC
                if fname.endswith(".class"):
                    if OLD_B in data:
                        data = patch_bytes(data)
                        n_class += 1
                elif fname == "plugin.yml":
                    if OLD_B in data or OLD_FQ.encode() in data:
                        data = patch_yml(data)
                # any other resource referencing main
                elif OLD_B in data:
                    data = patch_bytes(data)
                zi = zipfile.ZipInfo(fname, date_time=it.date_time)
                zi.external_attr = it.external_attr
                zout.writestr(zi, data, zipfile.ZIP_DEFLATED, compresslevel=9)
    print(f"{jar:55s}  classes-patched={n_class}")

print("\nDone -> obfuscated-final7/")
