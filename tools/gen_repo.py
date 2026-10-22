#!/usr/bin/env python
"""Regenerate a local repo index from live probes.

The mapping of ES-DE system -> archive.org collection / Minerva path lives in
`tools/sources.local.json`, which is **gitignored**. This script contains no
source data: the engine repository publishes no list, so the map is yours.

    python tools/gen_repo.py            # writes repo/index.json
    python tools/gen_repo.py --check    # exit 1 if repo/index.json is stale

Two sources of truth, no per-collection scraping:

  * Minerva: the two top-level directory listings enumerate every collection.
    A collection appearing there IS proof it exists, so this is 2 requests
    total, not one per system. Entry counts are runtime data.
  * archive.org: collection ids are probed for non-zero numFound, because a
    wrong id silently returns zero items instead of 404ing.

Per-collection Minerva scraping (78 requests) trips Cloudflare's rate limit and
is the wrong design anyway: a torrent carries its own file list, so the app
reads thousands of filenames from one .torrent instead of scraping per game.
"""

import json
import os
import re
import sys
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UA = {"User-Agent": "caddy-repo-generator/1.0 (+https://github.com/rlatksk/caddy)"}
MINERVA_BASE = "https://minerva-archive.org"
SOURCES = os.path.join(ROOT, "tools", "sources.local.json")


def get(url, timeout=60):
    req = urllib.request.Request(url, headers=UA)
    return urllib.request.urlopen(req, timeout=timeout).read().decode("utf-8", "replace")


def load_sources():
    if not os.path.exists(SOURCES):
        print(
            "missing tools/sources.local.json\n"
            "That file holds your system -> collection map and is deliberately not\n"
            "in this repository. See repo/index.example.json for the shape.",
            file=sys.stderr,
        )
        raise SystemExit(2)
    with open(SOURCES, encoding="utf-8") as f:
        data = json.load(f)
    return data.get("archiveOrg", {}), data.get("minerva", {})


def minerva_listing():
    """Both top-level listings in 2 requests. Returns {top: set(collection)}."""
    out = {}
    for top in ("No-Intro", "Redump"):
        html = get("%s/browse/%s/" % (MINERVA_BASE, top))
        out[top] = {
            urllib.parse.unquote(name)
            for name in re.findall(r'/browse/\./%s/([^"/]+)/' % top, html)
        }
    return out


def archive_count(collection):
    url = (
        "https://archive.org/advancedsearch.php?q=collection%3A"
        + urllib.parse.quote(collection)
        + "&fl[]=identifier&rows=0&output=json"
    )
    return json.loads(get(url, 30))["response"]["numFound"]


def es_systems():
    with open(os.path.join(ROOT, "schema", "systems.json"), encoding="utf-8") as f:
        return {name for name, _ in json.load(f)}


def build():
    archive_org, minerva = load_sources()
    systems = es_systems()
    errors = []

    ao_collections, ao_systems = {}, []
    for collection, system in archive_org.items():
        if system not in systems:
            errors.append("%r is not an ES-DE system name" % system)
            continue
        if archive_count(collection):
            ao_collections[system] = collection
            ao_systems.append(system)
        else:
            print("  drop archive.org %s: 0 items" % collection, file=sys.stderr)

    listing = minerva_listing()
    mi_paths, mi_systems = {}, []
    for system, (top, name) in sorted(minerva.items()):
        if system not in systems:
            errors.append("%r is not an ES-DE system name" % system)
            continue
        if name not in listing[top]:
            errors.append("Minerva has no %s/%s" % (top, name))
            continue
        mi_paths[system] = "%s/%s" % (top, name)
        mi_systems.append(system)

    if errors:
        for e in errors:
            print("ERROR " + e, file=sys.stderr)
        raise SystemExit(1)

    sources = []
    if ao_collections:
        sources.append({
            "id": "archive_org",
            "name": "Internet Archive",
            "version": "1.0.0",
            "transport": "http",
            "adapter": "archive_org",
            "systems": sorted(ao_systems),
            "config": {"collections": ao_collections},
        })
    if mi_paths:
        sources.append({
            "id": "minerva",
            "name": "Minerva Archive",
            "version": "1.0.0",
            "transport": "torrent",
            "adapter": "minerva",
            "systems": sorted(mi_systems),
            "config": {"baseUrl": MINERVA_BASE, "paths": mi_paths},
        })

    return {"schemaVersion": 1, "name": "Local Repo", "sources": sources}


def main():
    index = build()
    text = json.dumps(index, indent=2) + "\n"
    target = os.path.join(ROOT, "repo", "index.json")

    if "--check" in sys.argv:
        try:
            with open(target, encoding="utf-8") as f:
                current = f.read()
        except FileNotFoundError:
            current = ""
        if current != text:
            print("repo/index.json is stale; run tools/gen_repo.py", file=sys.stderr)
            return 1
        print("repo/index.json is current")
        return 0

    with open(target, "w", encoding="utf-8") as f:
        f.write(text)
    covered = {s for src in index["sources"] for s in src["systems"]}
    print("wrote repo/index.json: %d sources, %d systems" % (len(index["sources"]), len(covered)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
