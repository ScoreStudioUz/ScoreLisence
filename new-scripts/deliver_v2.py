"""Copy final9 jars (with manifest fix) into ScorePlugins delivery folder."""
import os, shutil

SRC = "/workspaces/ScoreLisence/obfuscated-final9"
DST = "/workspaces/ScoreLisence/dist"

LICENSED = {
    "ScoreAirdrop-1.0.0-obf.jar": "ScoreAirdrop.jar",
    "licensed-ScoreAutoBuyer-1.0.0-obf.jar": "ScoreAutoBuyer.jar",
    "licensed-ScoreHWAuction-obf.jar": "ScoreHWAuction.jar",
    "licensed-ScoreLicense-1.0.0-obf.jar": "ScoreLicense.jar",
    "licensed-ScorePvP-1.0.0-obf.jar": "ScorePvP.jar",
    "licensed-ScoreTpa-obf.jar": "ScoreTpa.jar",
}
NOLIC = {
    "nolic-ScoreAirdrop-1.0.0-obf.jar": "ScoreAirdrop-NoLic.jar",
    "nolic-ScoreAutoBuyer-1.0.0-obf.jar": "ScoreAutoBuyer-NoLic.jar",
    "nolic-ScoreHWAuction-obf.jar": "ScoreHWAuction-NoLic.jar",
    "nolic-ScorePvP-1.0.0-obf.jar": "ScorePvP-NoLic.jar",
    "nolic-ScoreTpa-1.1.0-obf.jar": "ScoreTpa-NoLic.jar",
}

lic_dir = os.path.join(DST, "licensed")
nolic_dir = os.path.join(DST, "no-licensed")
os.makedirs(lic_dir, exist_ok=True)
os.makedirs(nolic_dir, exist_ok=True)

for s_name, d_name in LICENSED.items():
    shutil.copy(os.path.join(SRC, s_name), os.path.join(lic_dir, d_name))
    print(f"[license]    {d_name}  ({os.path.getsize(os.path.join(lic_dir, d_name)):,} bytes)")

for s_name, d_name in NOLIC.items():
    src_path = os.path.join(SRC, s_name)
    if not os.path.exists(src_path):
        print(f"[no-license] {s_name}: skipped (not built)")
        continue
    shutil.copy(src_path, os.path.join(nolic_dir, d_name))
    print(f"[no-license] {d_name}  ({os.path.getsize(os.path.join(nolic_dir, d_name)):,} bytes)")

print(f"\nDone -> {DST}")
