#!/usr/bin/env python3
"""Downloads the icons a dashboard.yaml refers to, so the dashboard shows them without calling any third-party CDN.

  fetch_icons.py dashboard.yaml OUT_DIR       (OUT_DIR is normally <data>/images/icons)

Icon names follow the gethomepage convention:
  proxmox.svg / grafana.png / name.webp  -> https://github.com/homarr-labs/dashboard-icons  (svg/, png/, webp/)
  guacamole (no extension)               -> png/guacamole.png
  mdi-download-box                       -> https://github.com/Templarian/MaterialDesign-SVG  (svg/download-box.svg)
Paths starting with / and https:// URLs are left alone. Existing files are kept. Exit code 1 if any icon could not be fetched.
"""
import re, sys, urllib.request, urllib.error
from pathlib import Path
import yaml

DI = "https://raw.githubusercontent.com/homarr-labs/dashboard-icons/main/"
MDI = "https://raw.githubusercontent.com/Templarian/MaterialDesign-SVG/master/svg/"
NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,80}$")
PNG = b"\x89PNG\r\n\x1a\n"


def icon_names(doc):
    found = []
    def walk(x):
        if isinstance(x, dict):
            v = x.get("icon")
            if isinstance(v, str): found.append(v)
            for c in x.values(): walk(c)
        elif isinstance(x, list):
            for c in x: walk(c)
    walk(doc)
    return sorted(set(found))


def local_file(icon):
    """The file name an icon is stored under (must match web/src/internal/components/Icon.tsx)."""
    if icon.startswith("mdi-"): return icon + ".svg"
    return icon if re.search(r"\.(svg|png|webp)$", icon) else icon + ".png"


def source_url(icon):
    if icon.startswith("mdi-"): return MDI + icon[4:] + ".svg"
    f = local_file(icon)
    return DI + f.rsplit(".", 1)[1] + "/" + f


def valid(data, fname):
    if fname.endswith(".png"): return data.startswith(PNG)
    if fname.endswith(".webp"): return data[:4] == b"RIFF" and data[8:12] == b"WEBP"
    head = data[:400].lstrip().lower()
    return (head.startswith(b"<svg") or head.startswith(b"<?xml")) and b"<script" not in data.lower()


def fetch(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "homelab-hub-fetch-icons"}), timeout=20) as r:
        return r.read(2_000_000)


def main(argv):
    if len(argv) != 3: sys.exit(__doc__)
    doc = yaml.safe_load(Path(argv[1]).read_text(encoding="utf-8"))
    out = Path(argv[2]); out.mkdir(parents=True, exist_ok=True)
    failed = 0
    for icon in icon_names(doc):
        if icon.startswith("/") or icon.startswith("https://"): continue
        if not NAME.match(icon): print(f"skip   {icon!r}: not a valid icon name"); failed += 1; continue
        fname = local_file(icon); dest = out / fname
        if dest.exists(): print(f"keep   {fname}"); continue
        candidates = [source_url(icon)]
        if "." not in icon and not icon.startswith("mdi-"): candidates.append(DI + "svg/" + icon + ".svg")  # bare name: png first, then svg
        for url in candidates:
            try:
                data = fetch(url)
            except (urllib.error.URLError, TimeoutError) as e:
                continue
            f2 = fname if url.endswith(fname) else icon + ".svg"
            if not valid(data, f2): continue
            (out / f2).write_bytes(data); print(f"got    {f2}  <- {url}"); break
        else:
            print(f"MISSING {icon}: not found in the icon repositories (the dashboard will show a letter badge)"); failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
