"""Verify every internal link in the packaged site resolves to a real asset,
mimicking the app's LocalServer path rules (dir -> index.html). Asset entry names
are also validated: Android's AssetManager only resolves '/'-separated names."""
import os, re, sys, zipfile, html

APK = r"D:\01-工作区\welkin-apk\Welkin-1.0.3.apk"
EXTRACT = r"D:\welkin-build\apk-assets"
WWW = os.path.join(EXTRACT, "assets", "www")

if os.path.isdir(EXTRACT):
    import shutil
    shutil.rmtree(EXTRACT)
os.makedirs(EXTRACT, exist_ok=True)

with zipfile.ZipFile(APK) as z:
    names = [n for n in z.namelist() if n.startswith("assets/")]
    bad = [n for n in names if "\\" in n]
    if bad:
        print(f"FATAL: {len(bad)} asset entries use backslashes (unreadable on Android), e.g. {bad[0]}")
        sys.exit(1)
    www = [n for n in names if n.startswith("assets/www/")]
    for n in www:
        target = os.path.join(EXTRACT, *n.split("/"))
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with z.open(n) as src, open(target, "wb") as dst:
            dst.write(src.read())
print(f"extracted {len(www)} site assets from the APK (all '/'-separated)")

ATTR = re.compile(r'(?:href|src)\s*=\s*"([^"]+)"', re.I)
files = set()
for root, _, fs in os.walk(WWW):
    for f in fs:
        files.add(os.path.relpath(os.path.join(root, f), WWW).replace(os.sep, "/"))

broken, checked = [], set()
for page in sorted(f for f in files if f.endswith(".html")):
    text = open(os.path.join(WWW, page), encoding="utf-8").read()
    for raw in ATTR.findall(text):
        url = html.unescape(raw)
        if url.startswith(("http://", "https://", "#", "mailto:", "data:", "javascript:")):
            continue
        if url.startswith("/"):
            target = url[1:]
        else:
            target = os.path.normpath(os.path.join(os.path.dirname(page), url)).replace(os.sep, "/")
        target = target.split("?")[0].split("#")[0]
        if not target or target in checked:
            continue
        checked.add(target)
        candidates = [target, target.rstrip("/") + "/index.html", target + "/index.html"]
        if target.endswith("/"):
            candidates = [target + "index.html"]
        if not any(c in files for c in candidates):
            broken.append((page, raw, target))

print(f"internal references checked: {len(checked)}")
if broken:
    print(f"BROKEN LINKS: {len(broken)}")
    for page, raw, target in broken[:40]:
        print(f"  {page} -> {raw}  (resolved: {target})")
    sys.exit(1)
print("all internal links resolve")

external = set()
for page in files:
    if not page.endswith(".html"):
        continue
    text = open(os.path.join(WWW, page), encoding="utf-8").read()
    for raw in ATTR.findall(text):
        url = html.unescape(raw)
        if url.startswith(("http://", "https://", "//")):
            external.add(url)
print("external references (opened in the system browser):")
for url in sorted(external):
    print("  " + url)


