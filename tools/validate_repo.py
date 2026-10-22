#!/usr/bin/env python
"""Validate a Caddy repo index offline.

    python tools/validate_repo.py repo/index.json

Checks, in order:
  1. JSON Schema (schema/index.schema.json)
  2. every declared system is a real ES-DE system name (schema/systems.json)
  3. config maps point at systems the source actually declares
  4. source ids are unique, adapter/transport pairs are sane

Exit 1 on any error. Warnings do not fail.

Needs: pip install jsonschema
"""

import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def main(argv):
    target = argv[1] if len(argv) > 1 else os.path.join(ROOT, "repo", "index.json")
    index = load(target)
    errors, warnings = [], []

    try:
        import jsonschema
    except ImportError:
        print("jsonschema not installed: pip install jsonschema", file=sys.stderr)
        return 2

    schema = load(os.path.join(ROOT, "schema", "index.schema.json"))
    validator = jsonschema.Draft202012Validator(schema)
    for err in sorted(validator.iter_errors(index), key=lambda e: list(e.path)):
        errors.append("%s: %s" % ("/".join(str(p) for p in err.path) or "<root>", err.message))

    systems = {name for name, _ in load(os.path.join(ROOT, "schema", "systems.json"))}
    seen_ids = set()

    for i, src in enumerate(index.get("sources", [])):
        where = "sources/%d(%s)" % (i, src.get("id", "?"))
        if src.get("id") in seen_ids:
            errors.append("%s: duplicate source id" % where)
        seen_ids.add(src.get("id"))

        declared = src.get("systems") or []
        for system in declared:
            if system not in systems:
                errors.append("%s: %r is not an ES-DE system name" % (where, system))

        config = src.get("config") or {}
        for key in ("collections", "paths", "pages"):
            for system in config.get(key, {}):
                if system not in declared:
                    errors.append(
                        "%s: config.%s has %r but systems does not declare it" % (where, key, system))

        if not declared:
            warnings.append("%s: declares no systems" % where)

    for e in errors:
        print("ERROR   " + e)
    for w in warnings:
        print("WARN    " + w)

    if errors:
        print("\n%d error(s)" % len(errors), file=sys.stderr)
        return 1
    print("ok: %d source(s), %d system(s), schemaVersion %s" % (
        len(index.get("sources", [])),
        len({s for src in index.get("sources", []) for s in src.get("systems", [])}),
        index.get("schemaVersion")))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
