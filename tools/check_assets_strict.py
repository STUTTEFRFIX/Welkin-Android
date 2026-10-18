"""Strict simulation of Android's AssetManager over the built APK, plus the
app's LocalServer path rules.

Android resolves assets by exact zip entry name relative to the assets root and
only understands '/' separators; entries stored with '\' are invisible. This
script therefore reads assets/www/** straight from the APK with that rule, then
runs the server's URL -> asset mapping over every internal link.
"""
import os, re, sys, zipfile, html
from urllib.parse import unquote as url_unquote

APK = r"D:\01-工作区\welkin-apk\Welkin-1.0.3.apk"


class AndroidAssets:
    """AssetManager substitute: open(path) -> bytes, list() -> children."""

    def __init__(self, apk):
        self.zip = zipfile.ZipFile(apk)
        self.names = [n for n in self.zip.namelist() if n.startswith("assets/")]
        self.assets = {n[len("assets/"):]: n for n in self.names}

    def open(self, path):
        if path not in self.assets:
            raise FileNotFoundError(path)
        return self.zip.read(self.assets[path])

    def children(self, path):
        prefix = path.rstrip("/") + "/" if path else ""
        kids, seen = [], set()
        for name in self.assets:
            if name.startswith(prefix):
                head = name[len(prefix):].split("/")[0]
                if head and head not in seen:
                    seen.add(head)
                    kids.append(head)
        return kids


class LocalServer:
    """Faithful port of LocalServer.handle() path resolution."""

    def __init__(self, assets):
        self.assets = assets

    def resolve(self, target):
        path = url_unquote(target.split("?")[0].split("#")[0])
        if path in ("", "/"):
            path = "/index.html"
        elif path.endswith("/"):
            path += "index.html"
        if ".." in path or "\\" in path or "\0" in path:
            return 403, None
        asset = "www" + path
        try:
            return 200, self.assets.open(asset)
        except (FileNotFoundError, IsADirectoryError):
            return 404, None


apk = AndroidAssets(APK)
server = LocalServer(apk)

# 1) Asset name sanity: what Android can actually see.
entry_names = apk.assets
bad = [n for n in entry_names if "\\" in n]
print(f"assets visible to AssetManager: {len(entry_names)}")
if bad:
    print(f"FATAL: {len(bad)} entries contain backslashes, e.g. {bad[0]}")
    sys.exit(1)
if "www/index.html" not in entry_names:
    print("FATAL: AssetManager cannot see www/index.html")
    sys.exit(1)
print("AssetManager can open: www/index.html,", end=" ")
for probe in ("www/assets/welkin/posts.js", "www/images/cover-1.webp", "www/favicon.ico"):
    print(probe[len("www/"):], end=" ")
    if probe not in entry_names:
        print(f"\nFATAL: missing {probe}")
        sys.exit(1)
print()

# 2) The app's own root request must return the homepage.
status, body = server.resolve("/")
if status != 200 or b'id="feature-stage"' not in body:
    print(f"FATAL: GET / -> {status}")
    sys.exit(1)
print("GET / -> 200, homepage bytes:", len(body))

# 3) Every internal reference in every page must resolve through the same rule.
ATTR = re.compile(r'(?:href|src)\s*=\s*"([^"]+)"', re.I)
pages = sorted(n for n in entry_names if n.startswith("www/") and n.endswith(".html"))
broken, checked = [], set()
for page in pages:
    text = apk.open(page).decode("utf-8")
    for raw in ATTR.findall(text):
        url = html.unescape(raw)
        if url.startswith(("http://", "https://", "#", "mailto:", "data:", "javascript:")):
            continue
        base = os.path.dirname(page[len("www/"):])
        target = url if url.startswith("/") else "/" + os.path.normpath(os.path.join(base, url)).replace(os.sep, "/")
        target = target.split("?")[0].split("#")[0]
        if target in checked:
            continue
        checked.add(target)
        status, _ = server.resolve(target)
        if status != 200:
            broken.append((page, raw, target, status))

print(f"pages: {len(pages)}, internal references checked: {len(checked)}")
if broken:
    print(f"BROKEN: {len(broken)}")
    for page, raw, target, status in broken[:20]:
        print(f"  {page}: {raw} -> {target} ({status})")
    sys.exit(1)
print("all internal references resolve through the app's AssetManager rules")

