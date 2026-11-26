#!/usr/bin/env python3
"""Remove the parts of the bundled Python that nothing in DFast can use (Tcl/Tk + X11 GUI stack,
IDLE, turtle, static libs, pkg-config/cmake metadata). pip, ensurepip and everything yt-dlp,
the PO-token provider and the terminal need are kept.

usage: trim_python_bundle.py IN.zip OUT.zip
Safety: after trimming, every ELF that is kept is checked; if any kept ELF still needs a removed
library the script exits 2 and writes nothing, so the build falls back to the untouched bundle."""
import re, subprocess, sys, zipfile, tempfile, os

L = "usr/lib/"
P = "usr/lib/python3.14/"
RULES = [
    r"^usr/lib/python3\.14/(idlelib|turtledemo|tkinter)/",
    r"^usr/lib/python3\.14/turtle\.py$",
    r"^usr/lib/python3\.14/lib-dynload/_tkinter[^/]*$",
    r"^usr/lib/python3\.14/site-packages/xcbgen/",
    r"^usr/lib/(tcl8\.6|tk8\.6|tcl8|itcl[^/]*|tdbc[^/]*|thread2[^/]*|sqlite3)/",
    r"^usr/lib/(pkgIndex\.tcl|tclConfig\.sh|tkConfig\.sh|tclooConfig\.sh)$",
    r"^usr/lib/lib(tcl|tk|X11|Xau|Xdmcp|Xext|Xft|Xrender|Xss|xcb|freetype|fontconfig|png)[^/]*$",
    r"^usr/etc/fonts/",
    r"^usr/lib/[^/]*\.a$",
    r"^usr/lib/(pkgconfig|cmake)/",
]
RX = [re.compile(r) for r in RULES]
def dead(n): return any(r.match(n) for r in RX)

def needed(data, tmp):
    with open(tmp, "wb") as f: f.write(data)
    out = subprocess.run(["readelf", "-d", tmp], capture_output=True, text=True).stdout
    return re.findall(r"\(NEEDED\)\s+Shared library: \[([^\]]+)\]", out)

def main(src, dst):
    zin = zipfile.ZipFile(src)
    names = zin.namelist()
    removed = {n for n in names if dead(n)}
    removed_libs = {os.path.basename(n) for n in removed if ".so" in os.path.basename(n)}
    bad = []
    tmp = tempfile.mktemp()
    for n in names:
        if n in removed or n.endswith("/"): continue
        if ".so" in os.path.basename(n):
            data = zin.read(n)
            if data[:4] != b"\x7fELF": continue
            for need in needed(data, tmp):
                if need in removed_libs: bad.append((n, need))
    if os.path.exists(tmp): os.remove(tmp)
    if bad:
        print("KEPT FILES STILL NEED REMOVED LIBRARIES:", bad[:10]); return 2
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
        for i in zin.infolist():
            if i.filename in removed: continue
            zi = zipfile.ZipInfo(i.filename, i.date_time); zi.external_attr = i.external_attr
            zi.compress_type = zipfile.ZIP_DEFLATED if not i.is_dir() else zipfile.ZIP_STORED
            zout.writestr(zi, zin.read(i.filename))
    print("removed %d entries, %d bytes uncompressed; %d -> %d bytes" % (
        len(removed), sum(zin.getinfo(n).file_size for n in removed), os.path.getsize(src), os.path.getsize(dst)))
    return 0

if __name__ == "__main__": sys.exit(main(sys.argv[1], sys.argv[2]))
