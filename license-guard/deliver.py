"""Copy final8b jars into delivery folder with clean names."""
import os, shutil

SRC = "C:/Users/shoxg/OneDrive/Desktop/plugins/obfuscated-final8b"
DST = "C:/Users/shoxg/OneDrive/Desktop/ScoreLicense"

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

lic_dir = os.path.join(DST, "license")
nolic_dir = os.path.join(DST, "no-license")
os.makedirs(lic_dir, exist_ok=True)
os.makedirs(nolic_dir, exist_ok=True)

for src_name, dst_name in LICENSED.items():
    s = os.path.join(SRC, src_name)
    d = os.path.join(lic_dir, dst_name)
    shutil.copy(s, d)
    print(f"[license]    {dst_name}  ({os.path.getsize(d):,} bytes)")

for src_name, dst_name in NOLIC.items():
    s = os.path.join(SRC, src_name)
    d = os.path.join(nolic_dir, dst_name)
    shutil.copy(s, d)
    print(f"[no-license] {dst_name}  ({os.path.getsize(d):,} bytes)")

print(f"\nDone -> {DST}")
