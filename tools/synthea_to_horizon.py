#!/usr/bin/env python3
"""Reshape Synthea FHIR R4 bundles into the shape the ECDS mapping expects, then load them.

Synthea data exercises the plumbing but, as generated, cannot produce a valid ECDS row: four
columns the spec marks Required=Yes have no source in it.

    MemberKey    <- Patient.identifier[system=.../horizon-id]        Synthea has no Horizon ids
    MemberID     <- Patient.identifier[system=.../card-id]           same
    Ecdsaltid_01 <- Composition.identifier.value                     Synthea exports no Composition
    ClientSystemOfRecordID / Datasourcename
                 <- meta.tag[system=.../data-origin]                 Synthea sets no meta.tag

So this adds exactly those, and nothing else:

1. A data-origin tag on every resource in the bundle, alternating AthenaHealth / KonzaHealth per
   patient so one run exercises both feeds' constants.
2. `horizon-id` and `card-id` identifiers on each Patient, derived from the patient's index so
   they are stable across runs with the same seed.
3. One Composition per Encounter, listing that encounter's clinical records. Some entries go in a
   *nested* section on purpose: the R4 `Composition-entry` search parameter indexes top-level
   sections only, which is the bug `DaoDocumentResolver` was rewritten to survive, and a volume
   run should exercise the recursive walk rather than the easy path.

References are left as the `urn:uuid:` forms Synthea emits and the Composition is added as another
entry in the same transaction bundle, so the server resolves everything in one go and no ids have
to be guessed in advance.

    python3 tools/synthea_to_horizon.py --in ~/synthea/output/fhir --out ./synthea-horizon
    python3 tools/synthea_to_horizon.py --in ~/synthea/output/fhir --base http://localhost:8000
"""
import argparse
import base64
import json
import pathlib
import sys
import urllib.error
import urllib.request

TAG_SYSTEM = "http://horizonblue.com/fhir/meta/tag/data-origin"
IDENTIFIER_PREFIX = "http://horizonblue.com/fhir/patient/identifier/"
FEEDS = ("AthenaHealth", "KonzaHealth")

# Types that become ECDS rows and so are worth listing in a document, matching
# EcdsRowBuilder.ROW_PRODUCING_TYPES_IN_ORDER minus Encounter (the document names that separately).
LISTABLE = ("Condition", "Observation", "Procedure", "DiagnosticReport",
            "Immunization", "MedicationRequest", "MedicationDispense")

# Loaded first: patient bundles reference these Organizations and Practitioners.
INFRASTRUCTURE_PREFIXES = ("hospitalInformation", "practitionerInformation")


def tag(resource, feed):
    meta = resource.setdefault("meta", {})
    tags = meta.setdefault("tag", [])
    if not any(t.get("system") == TAG_SYSTEM for t in tags):
        tags.append({"system": TAG_SYSTEM, "code": feed})


def add_member_ids(patient, index):
    identifiers = patient.setdefault("identifier", [])
    existing = {i.get("system") for i in identifiers}
    # Distinct values: the mapping requires MemberID != MemberKey and blanks MemberID otherwise.
    for suffix, value in (("horizon-id", f"9{index:06d}"), ("card-id", f"C{index:06d}")):
        system = IDENTIFIER_PREFIX + suffix
        if system not in existing:
            identifiers.append({"system": system, "value": value})


def entry_ref(entry):
    """How other entries in this bundle refer to this one."""
    full_url = entry.get("fullUrl")
    if full_url:
        return full_url
    resource = entry.get("resource", {})
    if resource.get("id"):
        return f"{resource['resourceType']}/{resource['id']}"
    return None


def compositions_for(entries, patient_ref, index):
    """One Composition per Encounter, over the records that point at that encounter."""
    by_encounter = {}
    for entry in entries:
        resource = entry.get("resource", {})
        if resource.get("resourceType") not in LISTABLE:
            continue
        encounter = (resource.get("encounter") or {}).get("reference")
        if encounter:
            by_encounter.setdefault(encounter, []).append(entry)

    out = []
    encounters = [e for e in entries if e.get("resource", {}).get("resourceType") == "Encounter"]
    for n, encounter_entry in enumerate(encounters):
        encounter_ref = entry_ref(encounter_entry)
        records = by_encounter.get(encounter_ref, [])
        refs = [r for r in (entry_ref(e) for e in records) if r]
        if not refs:
            continue
        # Half top-level, half one section down: both document routes in every run.
        split = (len(refs) + 1) // 2
        top, nested = refs[:split], refs[split:]
        section = [{"title": "Results", "entry": [{"reference": r} for r in top]}]
        if nested:
            section.append({
                "title": "Encounters",
                "section": [{"title": "Nested records",
                             "entry": [{"reference": r} for r in nested]}],
            })
        period = (encounter_entry.get("resource", {}).get("period") or {})
        composition = {
            "resourceType": "Composition",
            "identifier": {"value": f"doc-{index:06d}-{n:04d}"},
            "status": "final",
            "type": {"coding": [{"system": "http://loinc.org", "code": "34133-9"}]},
            "subject": {"reference": patient_ref},
            "encounter": {"reference": encounter_ref},
            "date": period.get("start", "2026-01-01T00:00:00Z"),
            "title": "CCD",
            "section": section,
        }
        out.append({
            "fullUrl": f"urn:uuid:composition-{index:06d}-{n:04d}",
            "resource": composition,
            "request": {"method": "POST", "url": "Composition"},
        })
    return out


def transform(bundle, index, feed, with_compositions=True):
    """Returns (bundle, stats). Mutates in place; the caller owns the parsed copy."""
    entries = bundle.get("entry") or []
    patients = [e for e in entries
                if e.get("resource", {}).get("resourceType") == "Patient"]

    for patient in patients:
        add_member_ids(patient["resource"], index)

    added = []
    if with_compositions and patients:
        patient_ref = entry_ref(patients[0])
        if patient_ref:
            added = compositions_for(entries, patient_ref, index)
            entries.extend(added)

    # After the Compositions are in, not before: an untagged resource lands in the converter's
    # "records with no data-origin" counter, and a synthesised document skewing that counter
    # would make the volume run's diagnostics lie.
    for entry in entries:
        resource = entry.get("resource")
        if resource:
            tag(resource, feed)

    bundle["entry"] = entries
    return bundle, {"resources": len(entries), "patients": len(patients),
                    "compositions": len(added)}


def post(base, body, auth_header, timeout):
    request = urllib.request.Request(
        base.rstrip("/"),
        data=json.dumps(body).encode(),
        method="POST",
        headers={"Content-Type": "application/fhir+json",
                 "Accept": "application/fhir+json",
                 "Authorization": auth_header})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.status, json.loads(response.read() or b"{}")


def ordered_bundles(in_dir):
    """Infrastructure bundles first; patient bundles after, in a stable order."""
    files = sorted(p for p in in_dir.glob("*.json"))
    infra = [p for p in files if p.name.startswith(INFRASTRUCTURE_PREFIXES)]
    return infra + [p for p in files if p not in set(infra)]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--in", dest="in_dir", type=pathlib.Path, required=True,
                        help="Synthea's output/fhir directory")
    parser.add_argument("--out", type=pathlib.Path,
                        help="write transformed bundles here instead of posting them")
    parser.add_argument("--base", help="FHIR base URL to POST the transformed bundles to")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="password")
    parser.add_argument("--auth-header", help="full Authorization header; overrides user/password")
    parser.add_argument("--timeout", type=int, default=300, help="per-bundle seconds")
    parser.add_argument("--limit", type=int, help="only the first N bundles")
    parser.add_argument("--no-composition", action="store_true",
                        help="skip Composition synthesis, leaving Ecdsaltid_01 blank")
    args = parser.parse_args(argv)

    if not args.out and not args.base:
        parser.error("give --out, --base, or both")
    if not args.in_dir.is_dir():
        parser.error(f"not a directory: {args.in_dir}")

    files = ordered_bundles(args.in_dir)
    if args.limit:
        files = files[:args.limit]
    if not files:
        print(f"no *.json in {args.in_dir}", file=sys.stderr)
        return 2
    if args.out:
        args.out.mkdir(parents=True, exist_ok=True)

    auth = args.auth_header or "Basic " + base64.b64encode(
        f"{args.username}:{args.password}".encode()).decode()

    totals = {"bundles": 0, "resources": 0, "patients": 0, "compositions": 0, "failed": 0}
    patient_index = 0
    for path in files:
        try:
            bundle = json.loads(path.read_text())
        except (OSError, json.JSONDecodeError) as e:
            print(f"  skip {path.name}: {e}", file=sys.stderr)
            totals["failed"] += 1
            continue
        if bundle.get("resourceType") != "Bundle":
            print(f"  skip {path.name}: not a Bundle", file=sys.stderr)
            continue

        is_infra = path.name.startswith(INFRASTRUCTURE_PREFIXES)
        if not is_infra:
            patient_index += 1
        feed = FEEDS[patient_index % len(FEEDS)]
        bundle, stats = transform(bundle, patient_index, feed,
                                  with_compositions=not args.no_composition and not is_infra)

        if args.out:
            (args.out / path.name).write_text(json.dumps(bundle))
        if args.base:
            try:
                status, response = post(args.base, bundle, auth, args.timeout)
                bad = [e for e in response.get("entry", [])
                       if not str(e.get("response", {}).get("status", "")).startswith(("200", "201"))]
                if bad:
                    totals["failed"] += 1
                    print(f"  {path.name}: HTTP {status}, {len(bad)} entr(y/ies) rejected")
                    print("    " + json.dumps(bad[0].get("response", {}))[:300])
            except urllib.error.HTTPError as e:
                totals["failed"] += 1
                print(f"  {path.name}: HTTP {e.code} "
                      f"{e.read().decode(errors='replace')[:300]}", file=sys.stderr)
            except urllib.error.URLError as e:
                print(f"cannot reach {args.base}: {e.reason}", file=sys.stderr)
                return 1

        totals["bundles"] += 1
        for k in ("resources", "patients", "compositions"):
            totals[k] += stats[k]
        if totals["bundles"] % 25 == 0:
            print(f"  {totals['bundles']}/{len(files)} bundles, "
                  f"{totals['resources']} resources, "
                  f"{totals['compositions']} compositions", flush=True)

    print(f"\n{totals['bundles']} bundle(s): {totals['resources']} resources, "
          f"{totals['patients']} patients, {totals['compositions']} compositions synthesised, "
          f"{totals['failed']} failure(s)")
    return 1 if totals["failed"] else 0


if __name__ == "__main__":
    sys.exit(main())
