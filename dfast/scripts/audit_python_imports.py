#!/usr/bin/env python3
"""Static check for the trimmed Python bundle: no kept .py file (and not yt-dlp itself) imports a removed module.
usage: audit_python_imports.py TRIMMED.zip [yt-dlp zipapp ...]   exit 0 = clean"""
import ast, io, sys, zipfile
REMOVED = {"tkinter", "_tkinter", "turtle", "turtledemo", "idlelib", "xcbgen"}
def imports(src):
    try: tree = ast.parse(src)
    except Exception: return set()
    out = set()
    for n in ast.walk(tree):
        if isinstance(n, ast.Import): out |= {a.name.split(".")[0] for a in n.names}
        elif isinstance(n, ast.ImportFrom) and n.level == 0 and n.module: out.add(n.module.split(".")[0])
    return out
hits = []
def scan(z, label):
    for i in z.namelist():
        if not i.endswith(".py"): continue
        top = i.split("/")
        if label == "bundle" and len(top) > 3 and top[3] in ("idlelib", "turtledemo", "tkinter", "xcbgen"): continue
        if label == "bundle" and i.endswith("python3.14/turtle.py"): continue
        bad = imports(z.read(i)) & REMOVED
        if bad: hits.append((label, i, sorted(bad)))
scan(zipfile.ZipFile(sys.argv[1]), "bundle")
for a in sys.argv[2:]:
    scan(zipfile.ZipFile(a), "app:" + a)
for h in hits: print(h)
print("files importing a removed module:", len(hits)); sys.exit(1 if hits else 0)
