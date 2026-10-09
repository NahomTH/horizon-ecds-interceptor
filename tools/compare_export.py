#!/usr/bin/env python3
"""Diff a local $export run against the expected rows for the mock-export fixture.

Rows are compared as a multiset, not in order: the interceptor emits them in patient-compartment
order while `expected-mock-export.csv` is sorted, and neither order is part of the contract.

Usage:
    python3 tools/seed_local.py --base http://localhost:8000
    python3 tools/run_ecds_export.py --base http://localhost:8000 --out ./ecds-run
    python3 tools/compare_export.py ./ecds-run
"""
import argparse
import collections
import csv
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
EXPECTED = ROOT / "tools/expected-mock-export.csv"


def rows(path):
    """(header, [row, ...]) from one CSV, read with the same quoting rules it was written with."""
    with open(path, newline="") as handle:
        reader = csv.reader(handle)
        all_rows = [r for r in reader if r]
    if not all_rows:
        return [], []
    return all_rows[0], all_rows[1:]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("run_dir", type=pathlib.Path,
                        help="the --out directory run_ecds_export.py wrote")
    parser.add_argument("--expected", type=pathlib.Path, default=EXPECTED)
    parser.add_argument("--prefix", default="Patient",
                        help="data file prefix in the manifest (default Patient)")
    args = parser.parse_args(argv)

    expected_header, expected_rows = rows(args.expected)

    data_files = sorted(args.run_dir.glob(f"{args.prefix}-*.csv"))
    if not data_files:
        print(f"no {args.prefix}-*.csv in {args.run_dir}", file=sys.stderr)
        return 2

    actual_rows, headers = [], set()
    for path in data_files:
        header, body = rows(path)
        if header:
            headers.add(tuple(header))
        actual_rows.extend(body)
    print(f"{len(data_files)} data file(s), {len(actual_rows)} row(s); "
          f"expected {len(expected_rows)} row(s)")

    failures = 0
    if len(headers) != 1:
        print(f"FAIL  data files disagree on the header ({len(headers)} distinct)")
        failures += 1
    elif list(next(iter(headers))) != expected_header:
        print("FAIL  header differs from expected")
        for i, (a, b) in enumerate(zip(next(iter(headers)), expected_header)):
            if a != b:
                print(f"        col {i}: got {a!r} want {b!r}")
        failures += 1
    else:
        print(f"PASS  header matches ({len(expected_header)} columns)")

    got = collections.Counter(tuple(r) for r in actual_rows)
    want = collections.Counter(tuple(r) for r in expected_rows)
    if got == want:
        print(f"PASS  all {len(expected_rows)} rows match as a multiset")
    else:
        failures += 1
        missing, extra = want - got, got - want
        print(f"FAIL  {sum(missing.values())} missing, {sum(extra.values())} unexpected")
        for label, counter in (("missing", missing), ("unexpected", extra)):
            for row, n in list(counter.items())[:5]:
                cells = {expected_header[i]: v
                         for i, v in enumerate(row) if i < len(expected_header) and v}
                print(f"  {label} x{n}: {cells}")

    print("\nOK" if failures == 0 else f"\n{failures} check(s) failed")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
