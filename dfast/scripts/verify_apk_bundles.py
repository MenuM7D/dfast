#!/usr/bin/env python3
"""Smoke check for release APKs: every runtime package is still in place and complete.
Checks the things that make yt-dlp, ffmpeg, aria2c and the terminal start; it cannot run them.
usage: verify_apk_bundles.py APK..."""
import io, sys, zipfile
NEED_LIBS = ["libpython.zip.so", "libffmpeg.zip.so", "libaria2c.zip.so", "libbash.zip.so", "libqjs.so"]
PY_KEEP = ["usr/lib/python3.14/ensurepip/", "usr/lib/python3.14/site-packages/pip/", "usr/lib/python3.14/ssl.py",
           "usr/lib/python3.14/sqlite3/", "usr/lib/python3.14/json/", "usr/lib/python3.14/zipfile/", "usr/lib/python3.14/curses/",
           "usr/lib/python3.14/site-packages/requests/", "usr/lib/python3.14/site-packages/websockets/"]
PY_GONE = ["usr/lib/tcl8.6/", "usr/lib/tk8.6/", "usr/lib/python3.14/tkinter/", "usr/lib/python3.14/idlelib/"]
bad = 0
for apk in sys.argv[1:]:
    z = zipfile.ZipFile(apk)
    abis = sorted({n.split("/")[1] for n in z.namelist() if n.startswith("lib/") and n.count("/") >= 2})
    print("==", apk, "abis:", abis)
    for abi in abis:
        for lib in NEED_LIBS:
            n = "lib/%s/%s" % (abi, lib)
            if n not in z.namelist(): print("MISSING", n); bad += 1; continue
            if lib.endswith(".zip.so"):
                inner = zipfile.ZipFile(io.BytesIO(z.read(n)))
                if inner.testzip() is not None: print("CORRUPT", n); bad += 1
                names = inner.namelist()
                if lib == "libpython.zip.so":
                    for k in PY_KEEP:
                        if not any(x.startswith(k) for x in names): print("PYTHON LOST", k, abi); bad += 1
                    for k in PY_GONE:
                        if any(x.startswith(k) for x in names): print("PYTHON NOT TRIMMED", k, abi); bad += 1
        print("  %s ok, python bundle %d bytes" % (abi, z.getinfo("lib/%s/libpython.zip.so" % abi).file_size))
sys.exit(1 if bad else 0)
