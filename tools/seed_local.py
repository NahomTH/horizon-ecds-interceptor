#!/usr/bin/env python3
"""Seed a local FHIR server with the mock-export fixture the equivalence test uses.

The point of using this fixture rather than Synthea: its expected output is already known.
`tools/expected-mock-export.csv` is the POC's in-memory mapping over these same resources, so a
local `$export` can be diffed against it. Synthea data exercises the plumbing but has no
Horizon data-origin tags, no Composition and nothing to compare against.

Resources are PUT by id inside one transaction bundle, so the ids in the fixture survive and
the references between them keep resolving.
"""
import argparse
import base64
import json
import pathlib
import sys
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
MOCK_DIR = ROOT / "src/test/resources/mock-export"
COMPOSITION = ROOT / "src/test/resources/mock-export-composition.json"


def resources():
    """Every fixture resource, in the same order the test loads them."""
    out = []
    for path in sorted(MOCK_DIR.glob("*.ndjson")):
        for line in path.read_text().splitlines():
            if line.strip():
                out.append(json.loads(line))
    out.append(json.loads(COMPOSITION.read_text()))
    return out


def bundle(theResources):
    return {
        "resourceType": "Bundle",
        "type": "transaction",
        "entry": [
            {
                "resource": r,
                "request": {"method": "PUT", "url": f"{r['resourceType']}/{r['id']}"},
            }
            for r in theResources
        ],
    }


def post(base, body, auth_header):
    request = urllib.request.Request(
        base.rstrip("/"),
        data=json.dumps(body).encode(),
        method="POST",
        headers={"Content-Type": "application/fhir+json", "Accept": "application/fhir+json",
                 "Authorization": auth_header},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.status, json.loads(response.read() or b"{}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8000", help="FHIR base URL")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="password")
    parser.add_argument("--auth-header",
                        help="full Authorization header value; overrides user/password")
    parser.add_argument("--dry-run", action="store_true",
                        help="print the bundle instead of sending it")
    args = parser.parse_args(argv)

    found = resources()
    counts = {}
    for r in found:
        counts[r["resourceType"]] = counts.get(r["resourceType"], 0) + 1
    # stderr, so --dry-run writes nothing but the bundle to stdout and stays pipeable.
    print(f"fixture: {len(found)} resources -- "
          + ", ".join(f"{k} x{v}" for k, v in sorted(counts.items())), file=sys.stderr)

    body = bundle(found)
    if args.dry_run:
        json.dump(body, sys.stdout, indent=1)
        return 0

    auth = args.auth_header or "Basic " + base64.b64encode(
        f"{args.username}:{args.password}".encode()).decode()
    try:
        status, response = post(args.base, body, auth)
    except urllib.error.HTTPError as e:
        print(f"seed failed: HTTP {e.code}\n{e.read().decode(errors='replace')[:2000]}",
              file=sys.stderr)
        return 1
    except urllib.error.URLError as e:
        print(f"seed failed: cannot reach {args.base} ({e.reason})", file=sys.stderr)
        return 1

    entries = response.get("entry", [])
    bad = [e for e in entries
           if not str(e.get("response", {}).get("status", "")).startswith(("200", "201"))]
    print(f"HTTP {status}: {len(entries)} entries, {len(bad)} not 200/201")
    for e in bad[:10]:
        print("  " + json.dumps(e.get("response", {}))[:300])
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
