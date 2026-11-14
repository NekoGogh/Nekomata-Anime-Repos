#!/usr/bin/env python3
"""Generate index.json, index.min.json and index.html for this Aniyomi/AniZen repo.

The script reads the module metadata from every ``src/<lang>/<module>/build.gradle``
file and includes only modules whose release APK is present in ``apk/``.
"""

from __future__ import annotations

import hashlib
import html
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src"
APK_DIR = ROOT / "apk"

EXT_BLOCK = re.compile(r"ext\s*\{(.*?)\}", re.S)
PROPERTY = re.compile(r"(\w+)\s*=\s*'([^']*)'")
NUMBER_PROPERTY = re.compile(r"(\w+)\s*=\s*(\d+)")
BOOL_PROPERTY = re.compile(r"(\w+)\s*=\s*(true|false)")
BASE_URL = re.compile(r'override\s+val\s+baseUrl\s*=\s*"([^"]+)"')


def source_id(name: str, lang: str, version_id: int = 1) -> str:
    digest = hashlib.md5(f"{name.lower()}/{lang}/{version_id}".encode()).hexdigest()[:16]
    return str(int(digest, 16) & 0x7FFFFFFFFFFFFFFF)


def read_module(module_dir: Path, lang: str) -> dict | None:
    build_file = module_dir / "build.gradle"
    text = build_file.read_text(encoding="utf-8")

    block = EXT_BLOCK.search(text)
    if not block:
        return None

    values: dict[str, str] = {}
    for key, value in PROPERTY.findall(block.group(1)):
        values[key] = value
    for key, value in NUMBER_PROPERTY.findall(block.group(1)):
        values.setdefault(key, value)
    for key, value in BOOL_PROPERTY.findall(block.group(1)):
        values[key] = value

    name = values.get("extName")
    version_code = values.get("extVersionCode")
    if not name or not version_code:
        return None

    lib_version = values.get("libVersion", "16")
    nsfw = values.get("isNsfw", "false").lower() == "true"
    pkg = f"eu.kanade.tachiyomi.animeextension.{lang}.{module_dir.name}"

    base_url = ""
    for kotlin_file in (module_dir / "src").rglob("*.kt"):
        match = BASE_URL.search(kotlin_file.read_text(encoding="utf-8"))
        if match:
            base_url = match.group(1)
            break

    apk_name = f"aniyomi-{lang}.{module_dir.name}-v{lib_version}.{version_code}.apk"
    return {
        "module": module_dir.name,
        "lang": lang,
        "name": name,
        "pkg": pkg,
        "apk": apk_name,
        "code": int(version_code),
        "version": f"{lib_version}.{version_code}",
        "nsfw": 1 if nsfw else 0,
        "contentRating": 2 if nsfw else 0,
        "extensionLib": lib_version,
        "isTorrent": False,
        "sourceId": source_id(name, lang),
        "baseUrl": base_url,
    }


def load_modules() -> list[dict]:
    modules: list[dict] = []
    if not SRC.exists():
        return modules
    for lang_dir in sorted(p for p in SRC.iterdir() if p.is_dir()):
        for module_dir in sorted(p for p in lang_dir.iterdir() if p.is_dir()):
            if not (module_dir / "build.gradle").is_file():
                continue
            module = read_module(module_dir, lang_dir.name)
            if module:
                modules.append(module)
    return modules


def main() -> None:
    modules = load_modules()
    index = []

    for module in modules:
        apk_path = APK_DIR / module["apk"]
        if not apk_path.is_file():
            print(f"skip {module['pkg']}: {apk_path.name} not built yet")
            continue

        entry = {
            "name": f"Aniyomi: {module['name']}",
            "pkg": module["pkg"],
            "apk": module["apk"],
            "lang": module["lang"],
            "code": module["code"],
            "version": module["version"],
            "nsfw": module["nsfw"],
            "contentRating": module["contentRating"],
            "extensionLib": module["extensionLib"],
            "isTorrent": module["isTorrent"],
            "sources": [
                {
                    "name": module["name"],
                    "lang": module["lang"],
                    "id": module["sourceId"],
                    "baseUrl": module["baseUrl"],
                    "mirrorUrls": [],
                },
            ],
        }
        index.append(entry)

    index.sort(key=lambda item: item["pkg"])

    (ROOT / "index.json").write_text(
        json.dumps(index, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    (ROOT / "index.min.json").write_text(
        json.dumps(index, separators=(",", ":"), ensure_ascii=False), encoding="utf-8"
    )

    links = "\n".join(
        f'<a href="apk/{html.escape(item["apk"])}">{html.escape(item["name"])}</a>'
        for item in index
    )
    if not links:
        links = "<em>No APKs have been built yet. Run the Build Extensions workflow.</em>"

    (ROOT / "index.html").write_text(
        f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Akrab Anime Extensions</title>
<style>
body {{ font-family: system-ui, sans-serif; margin: 2rem auto; max-width: 52rem; padding: 0 1rem; line-height: 1.6; }}
a {{ display: block; padding: .35rem 0; }}
</style>
</head>
<body>
<h1>Akrab Anime Extensions</h1>
<p>Aniyomi / AniZen extension repository.</p>
{links}
</body>
</html>
""",
        encoding="utf-8",
    )

    print(f"wrote {len(index)} extension(s)")


if __name__ == "__main__":
    main()
