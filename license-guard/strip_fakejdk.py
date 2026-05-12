"""Remove the first batch of fake-JDK decoys from every jar.
Keeps the real-package per-plugin A-GR decoys + traps + Skidfuscator output."""
import os, zipfile, glob

SRC = "C:/Users/shoxg/OneDrive/Desktop/plugins/obfuscated-final4"
OUT = "C:/Users/shoxg/OneDrive/Desktop/plugins/obfuscated-final5"
os.makedirs(OUT, exist_ok=True)

DROP = (
    "com/sun/internal/",
    "java/lang/reflect/hidden/",
    "java/util/concurrent/atomic/internal_/",
    "sun/misc/unsafe/impl/",
    "sun/security/provider/hidden/",
    "com/oracle/jvm/hotspot/internal_/",
    "jdk/internal/misc/unsafe_/",
)

t_in = t_out = 0
for jar in sorted(glob.glob(os.path.join(SRC, "*.jar"))):
    name = os.path.basename(jar)
    out = os.path.join(OUT, name)
    in_size = os.path.getsize(jar); t_in += in_size
    dropped = 0
    with zipfile.ZipFile(jar, "r") as zin:
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
            for it in zin.infolist():
                if any(it.filename.startswith(p) for p in DROP):
                    dropped += 1; continue
                zout.writestr(it, zin.read(it.filename), zipfile.ZIP_DEFLATED, compresslevel=9)
    out_size = os.path.getsize(out); t_out += out_size
    delta = out_size - in_size
    print(f"{name:55s}  -{dropped:4d}  {in_size:>11,} -> {out_size:>11,}  ({delta:+,})")

print(f"\nTOTAL: {t_in:,} -> {t_out:,}  ({t_out-t_in:+,})")
