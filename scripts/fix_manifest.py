"""Add paperweight-mappings-namespace: spigot to MANIFEST.MF in all Score* jars
so Paper does NOT remap bytecode at runtime (which would break SHA-256 hashes).
"""
import os, zipfile, shutil

SRC = "/workspaces/ScoreLisence/obfuscated-final8b"
DST = "/workspaces/ScoreLisence/obfuscated-final9"
os.makedirs(DST, exist_ok=True)

MANIFEST_LINE = "paperweight-mappings-namespace: mojang\r\n"

for jar in os.listdir(SRC):
    if not jar.endswith(".jar"):
        continue
    s = os.path.join(SRC, jar)
    d = os.path.join(DST, jar)

    with zipfile.ZipFile(s, "r") as zin:
        items = zin.infolist()
        with zipfile.ZipFile(d, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
            for it in items:
                data = zin.read(it.filename)
                if it.filename == "META-INF/MANIFEST.MF":
                    text = data.decode("utf-8", errors="replace").replace("\r\n", "\n")
                    if "paperweight-mappings-namespace" not in text:
                        # Split into main section (before first blank line) and rest
                        if "\n\n" in text:
                            head, _, tail = text.partition("\n\n")
                            head = head.rstrip("\n") + "\n" + MANIFEST_LINE.replace("\r\n", "\n")
                            text = head + "\n" + tail
                        else:
                            text = text.rstrip("\n") + "\n" + MANIFEST_LINE.replace("\r\n", "\n")
                        text = text.replace("\n", "\r\n")
                        data = text.encode("utf-8")
                zout.writestr(it, data, zipfile.ZIP_DEFLATED, compresslevel=9)
    print(f"{jar}: manifest patched ({os.path.getsize(d):,} bytes)")

print(f"\nDone -> {DST}")
