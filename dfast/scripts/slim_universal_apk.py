#!/usr/bin/env python3
"""Drop the x86 and x86_64 libraries from the universal APK only (the per-architecture APKs for those
devices are published separately and are not touched), zipalign it and sign it again with the same key.
usage: slim_universal_apk.py APK KEYSTORE_PROPERTIES BUILD_TOOLS_DIR REFERENCE_APK
Exits non-zero (and leaves the original APK in place) if anything fails or the signer cert differs
from REFERENCE_APK's."""
import os, re, shutil, subprocess, sys, zipfile

def props(path):
    d = {}
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1); d[k.strip()] = v.strip()
    return d

def cert(apksigner, apk):
    out = subprocess.run([apksigner, "verify", "--print-certs", apk], capture_output=True, text=True, check=True).stdout
    return sorted(set(re.findall(r"certificate SHA-256 digest: ([0-9a-f]+)", out)))

def main(apk, kprops, bt, ref):
    apksigner, zipalign = os.path.join(bt, "apksigner"), os.path.join(bt, "zipalign")
    p = props(kprops)
    ks = p["signingConfig.storeFile"]
    if not os.path.isabs(ks): ks = os.path.join(os.path.dirname(os.path.abspath(kprops)), "app", ks)
    work = apk + ".slim"
    with zipfile.ZipFile(apk) as zin, zipfile.ZipFile(work, "w") as zout:
        for i in zin.infolist():
            n = i.filename
            if n.startswith("lib/x86/") or n.startswith("lib/x86_64/") or n.startswith("META-INF/"): continue
            zi = zipfile.ZipInfo(n, i.date_time); zi.compress_type = i.compress_type; zi.external_attr = i.external_attr
            zout.writestr(zi, zin.read(n))
    aligned = apk + ".aligned"
    subprocess.run([zipalign, "-f", "-p", "4", work, aligned], check=True)
    env = dict(os.environ, KS_PASS=p["signingConfig.storePassword"], KEY_PASS=p["signingConfig.keyPassword"])
    subprocess.run([apksigner, "sign", "--ks", ks, "--ks-key-alias", p["signingConfig.keyAlias"],
                    "--ks-pass", "env:KS_PASS", "--key-pass", "env:KEY_PASS", aligned], check=True, env=env)
    subprocess.run([apksigner, "verify", aligned], check=True)
    if cert(apksigner, aligned) != cert(apksigner, ref):
        print("signer certificate differs from the reference APK, keeping the original"); return 3
    for f in (work,): os.remove(f)
    before = os.path.getsize(apk); shutil.move(aligned, apk)
    idsig = aligned + ".idsig"
    if os.path.exists(idsig): os.remove(idsig)
    print("universal APK: %d -> %d bytes" % (before, os.path.getsize(apk))); return 0

if __name__ == "__main__": sys.exit(main(*sys.argv[1:5]))
