#!/usr/bin/env python3
"""Run an ECDS bulk export against a Smile CDR and download the files.

Standard library only. Kicks off $export with the ECDS output format, polls the status URL,
then downloads every file the manifest lists into an output folder:

    python3 tools/run_ecds_export.py --base http://localhost:8000 --out ./ecds-run

Files land as <label>-<n>.csv (label = the manifest "type": Patient for data, EcdsAudit for the
audit entries). Shipping them to Horizon is a separate step; this script stops at the download.

Exit codes: 0 ok, 10 kick-off failed, 11 export job failed, 12 timed out, 13 empty manifest,
14 a download failed, 15 bad usage.
"""
import argparse
import base64
import json
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

EPOCH = "1970-01-01T00:00:00Z"


def kick_off(base, output_format, types, type_filters, since, auth, timeout):
    params = [("_outputFormat", output_format), ("_since", since)]
    if types:
        params.append(("_type", ",".join(types)))
    for type_filter in type_filters:
        params.append(("_typeFilter", type_filter))
    url = f"{base.rstrip('/')}/$export?{urllib.parse.urlencode(params)}"
    request = urllib.request.Request(url, headers={
        "Accept": "application/fhir+json",
        "Prefer": "respond-async",
        # The server replays an identical job from a one-hour cache otherwise.
        "Cache-Control": "no-cache",
        **auth,
    })
    print(f"GET {url}")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        if response.status != 202:
            raise SystemExit(f"kick-off returned {response.status}, expected 202 (exit 10)")
        location = response.headers.get("Content-Location")
    if not location:
        print("no Content-Location header on the 202", file=sys.stderr)
        sys.exit(10)
    return location


def poll(status_url, auth, timeout, max_wait):
    started = time.time()
    while True:
        request = urllib.request.Request(status_url, headers={"Accept": "application/json", **auth})
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                if response.status == 200:
                    return json.loads(response.read().decode("utf-8"))
                progress = response.headers.get("X-Progress", "")
                retry = int(response.headers.get("Retry-After", "10") or 10)
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", errors="replace")
            print(f"export job failed: HTTP {error.code}\n{body[:2000]}", file=sys.stderr)
            sys.exit(11)
        elapsed = time.time() - started
        print(f"  in progress ({elapsed:.0f}s) {progress}")
        if elapsed > max_wait:
            print(f"timed out after {max_wait}s; resume with: {status_url}", file=sys.stderr)
            sys.exit(12)
        time.sleep(max(1, min(retry, 30)))


def download(manifest, out_dir, auth, timeout):
    outputs = manifest.get("output") or []
    if not outputs:
        print("manifest lists no output files", file=sys.stderr)
        sys.exit(13)
    out_dir.mkdir(parents=True, exist_ok=True)
    counters = {}
    for entry in outputs:
        label = entry.get("type", "file")
        counters[label] = counters.get(label, 0) + 1
        request = urllib.request.Request(entry["url"], headers={"Accept": "*/*", **auth})
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                content_type = response.headers.get("Content-Type", "")
                extension = ".csv" if "csv" in content_type else ".ndjson" if "ndjson" in content_type else ".bin"
                dest = out_dir / f"{label}-{counters[label]:03d}{extension}"
                with dest.open("wb") as handle:
                    while chunk := response.read(1 << 20):
                        handle.write(chunk)
        except urllib.error.HTTPError as error:
            print(f"download failed for {entry['url']}: HTTP {error.code}", file=sys.stderr)
            sys.exit(14)
        print(f"  {dest} ({dest.stat().st_size:,} bytes)")
    for error in manifest.get("error") or []:
        print(f"  server-side error file: {error.get('url')}")
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=1))
    print(f"{len(outputs)} file(s) in {out_dir}; manifest.json alongside")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8000", help="FHIR base URL")
    parser.add_argument("--output-format", default="text/csv", help="the _outputFormat the interceptor answers to")
    parser.add_argument("--type", dest="types", default="Patient",
                        help="comma-separated _type list (default: Patient — the patient-driven export)")
    parser.add_argument("--type-filter", dest="type_filters", action="append", default=[],
                        metavar="FILTER", help="e.g. 'Patient?_tag=KonzaHealth'; repeatable")
    parser.add_argument("--since", default=EPOCH,
                        help="_since; defaults to the epoch because the server otherwise exports only the last 24 hours")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="password")
    parser.add_argument("--auth-header", help="full Authorization header value, e.g. 'Bearer ...'; overrides user/password")
    parser.add_argument("--out", type=pathlib.Path, default=pathlib.Path("ecds-run"))
    parser.add_argument("--timeout", type=int, default=60, help="per-request seconds")
    parser.add_argument("--max-wait", type=int, default=1800, help="total polling seconds")
    args = parser.parse_args(argv)

    if args.auth_header:
        auth = {"Authorization": args.auth_header}
    else:
        token = base64.b64encode(f"{args.username}:{args.password}".encode()).decode()
        auth = {"Authorization": f"Basic {token}"}
    types = [t.strip() for t in args.types.split(",") if t.strip()]

    status_url = kick_off(args.base, args.output_format, types, args.type_filters, args.since, auth, args.timeout)
    print(f"polling {status_url}")
    manifest = poll(status_url, auth, args.timeout, args.max_wait)
    download(manifest, args.out, auth, args.timeout)
    return 0


if __name__ == "__main__":
    sys.exit(main())
