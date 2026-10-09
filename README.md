# Horizon ECDS interceptor

A Smile CDR interceptor that makes **ECDS** a bulk-export output format. Running

```
GET {fhir-base}/$export?_outputFormat=text/csv&_type=Patient&_since=1970-01-01T00:00:00Z
Prefer: respond-async
```

produces the 58-column Horizon ECDS supplemental-data CSV (NCQA/HEDIS MY26) instead of NDJSON,
one file per export chunk of members, plus an audit file, all listed in the export manifest.

This is the standalone project Niks asked for (2026-10-06): the POC (`poc-ecds-mapper`) is the
reference for the column logic, and everything that only made sense with a folder of NDJSON —
the two-pass indexer, the CLI, the download pipeline — is gone.

> **`_type=Patient`, nothing else.** Niks's NDJSON `$export` lists sixteen types. Reusing that
> command with `_outputFormat=text/csv` still produces correct rows, but the server fetches and
> expands every Condition, Observation and Procedure only to skip them, and each of those chunks
> still has to return *something*, so the manifest fills with header-only CSVs. The interceptor
> logs a warning when it sees that. Everything the rows need is reached from the Patient.

## How it works

1. Smile's `STORAGE_BULK_EXPORT_RESOURCE_CONVERT` pointcut (HAPI FHIR 8.14 / Smile CDR 2026.11)
   asks hooks for a converter when an `$export` is validated and for every work chunk.
   `EcdsBulkExportInterceptor` answers with `EcdsResourceConverter` when `_outputFormat` is the
   ECDS format (`text/csv` by default) and `null` otherwise, so NDJSON exports are untouched.
2. The export job runs as usual: it selects Patients (`_type=Patient`, `_typeFilter`, `_since`),
   fetches them in chunks of up to `bulk_export.file_max_capacity` (HAPI's
   `BulkExportFileMaximumCapacity`, 1,000 resources by default), and hands each chunk to the
   converter.
3. **Patient-driven fan-out** (Niks's design): for each Patient in the chunk, `PatientCompartmentSource`
   fetches the clinical records that reference that member — Encounter, Condition, Observation,
   Procedure, DiagnosticReport, Immunization, MedicationRequest, MedicationDispense, in that
   order — and `EcdsRowBuilder` turns each into zero or more rows. Practitioners, Organizations,
   Medications, Encounters and Compositions are reached on demand through `DaoResourceResolver`
   and `DaoDocumentResolver`, cached across chunks.
4. Rows stream into `EcdsFileSet`, which closes a file and starts a new one with its own header
   at `ECDS_MAX_FILE_BYTES` (100 MB by default). The chunk's audit entries become a second file
   labelled `EcdsAudit`. The export step stores each file as a `Binary` and lists it in the
   manifest under that label.
5. Something downloads the manifest's files — `tools/run_ecds_export.py` does, for testing —
   and ships them. S3 and scheduling are deliberately outside this project.

Each member's rows land together, in a fixed order, in one file. Chunks run concurrently on
batch worker threads; the converter keeps no per-call state and the resolvers are thread-safe
(`EcdsResourceConverterTest.concurrentChunksDoNotInterfere` pins that).

A resource-driven export (`_type=Condition,Observation,...`) also works: each chunk becomes rows
directly. If an export lists Patient and clinical types, the clinical chunks yield header-only
files so nothing is produced twice.

## Finding the source document — why this is not one search

`Ecdsaltid_01` is the CCD's document identifier, it is a **required** column, and the POC
populated it on 668 of 668 rows. Reproducing that inside the CDR is the one place where the
obvious translation of the CLI's index is wrong:

- The R4 `Composition-entry` search parameter is defined as **`Composition.section.entry`** —
  top-level sections only. A CCD nests sections, and this export puts some entries a level down,
  which is exactly why `CompositionIndex.indexSections` recursed. `Composition?entry=Type/id`
  cannot see a nested entry, so relying on it silently blanked a required column.

So `DaoDocumentResolver` runs **one** `Composition?patient=Patient/x` per member and walks those
Compositions in Java — recursively through nested sections, first document to list a resource
wins, the document's own `encounter` registered the same way — which is `CompositionIndex.add`
scoped to one member. The old reverse searches (`Composition?entry=`, then `?encounter=` /
`?entry=` on the record's encounter) remain as a fallback for a document whose subject is
someone else. Besides being correct, this turns one to three searches *per record* into one
search *per member*.

What is still **not** reproduced is `CompositionIndex.linkReferrers()`, which ran in both
directions over *every* reference a record made: outward (a listed record pulls in what it
references) and inward (a record that references anything in a document joins that document),
assigning the document whenever exactly one candidate resulted. Here the inward trace follows
`encounter` only. So the two can disagree on `Ecdsaltid_01` in both directions — a record whose
only route to a document was some other reference is left blank here, and a record the index
left blank because two different documents claimed it through two different references may be
populated here. Neither case appeared in the POC export; both are things the real-data diff
should look for.

## Layout

```
src/main/java/org/horizon/
  ecds/      the 58 columns and their derivation — lifted from the POC:
             EcdsColumn, EcdsRow, EcdsRowBuilder, PatientColumns, DateOfService, CodeColumns,
             ProviderColumns, ProviderTypeCrosswalk, RowExploder, FeedConstants, DataOrigin,
             CodeSystems, RequiredColumns, RequiredColumnsAudit, AuditLog, AuditCode, EcdsCsv
  fhir/      FhirPaths (data-absent-aware JSON navigation), the ResourceResolver and
             DocumentResolver interfaces, and their in-memory implementations
             (ReferenceIndex, CompositionIndex — used by tests)
  cdr/       everything that touches Smile: EcdsBulkExportInterceptor (the hook),
             EcdsResourceConverter, PatientCompartmentSource, DaoResourceResolver,
             DaoDocumentResolver, EcdsFileSet, EcdsConverterConfig
src/main/resources/provider-type-crosswalk.csv   NUCC taxonomy -> Horizon ProviderType
notes/                                           the mapping spec and crosswalk the tests reconcile against
src/test/resources/mock-export/                  the fixture; the equivalence test AND seed_local.py read it
src/test/resources/mock-export-composition.json  the one resource the mock export lacks
tools/  (standard library only, no pip install)
  run_ecds_export.py                             kick off $export, poll, download the manifest
  seed_local.py                                  PUT the fixture into a live server
  expected-mock-export.csv                       the reference mapping's answer for that fixture
  compare_export.py                              diff a run against it, as a multiset
  synthea_to_horizon.py                          add the 4 Required columns Synthea lacks
```

Column logic works on Jackson `JsonNode`; resources from storage are re-encoded to JSON with
HAPI's parser (the same one `$export` uses), which is what lets the POC's column classes and
their tests carry over.

## Build

```bash
mvn test            # 299 tests, including the equivalence test below
mvn package         # target/ecds-interceptor-1.0-SNAPSHOT.jar
```

Builds against `hapi-fhir 8.13.x-SNAPSHOT` (the published master snapshot that has the pointcut)
from the Central snapshots repository. **Pin `hapi.version` to the release the Horizon
environment runs — HAPI 8.14.0 / Smile CDR 2026.11 — before shipping**; a deliverable must not
depend on a snapshot that can be republished underneath it. Nothing here uses a Smile-specific
API, so `cdr-api-public-java` is not needed to compile.

The jar shades and relocates `commons-csv` **and `commons-io` and `commons-codec`**: commons-csv
1.14 is not self-contained — `CSVFormat`'s static initialiser touches
`org.apache.commons.io.output.AppendableOutputStream` — so shading it alone produced a jar that
threw `NoClassDefFoundError` from `EcdsCsv`'s class initialiser, on the first row written, unless
the host happened to carry a new enough commons-io. Verify after any dependency bump:

```bash
mvn -q package -DskipTests
# must print the header and exit 0, with nothing else on the classpath:
cat > /tmp/Probe.java <<'EOF'
import org.horizon.ecds.*;
public class Probe { public static void main(String[] a) { System.out.println(EcdsCsv.headerLine()); } }
EOF
javac -cp target/ecds-interceptor-1.0-SNAPSHOT.jar -d /tmp /tmp/Probe.java
java -cp target/ecds-interceptor-1.0-SNAPSHOT.jar:/tmp Probe
```

Three tests pin the parts a unit test would otherwise miss:

- `EcdsBulkExportInterceptorTest.hapiBindsTheHookAndGetsTheConverterBackThroughThePointcut`
  registers the interceptor with HAPI's real `InterceptorService` and drives the pointcut the way
  `ExpandResourcesConsumer.getConverter()` does. Every other test calls `converterFor` directly,
  which cannot catch the one failure that matters most: if the method signature stops matching
  what the pointcut declares, `registerInterceptor` throws and *Smile fails to start*.
- `PocOutputContractTest` compares the header this code writes against the header the reference
  mapper wrote in the 668-row run, byte for byte. `EcdsColumnTest` pins the enum to
  `notes/ECDS mapping.csv`; this pins the rendered line, which also covers quoting and the record
  separator.

`EcdsResourceConverterTest.patientDrivenExportProducesTheSameRowsAsTheInMemoryMapping` is the
acceptance test: the mock export plus a Composition, through the patient-driven converter over an
in-memory storage double, must produce exactly the rows the POC's in-memory mapping produces.
`FakeStorage` deliberately models the *real* `Composition-entry` search parameter (top-level
sections only), so the nested-section gap cannot hide behind a friendlier fake again.

Re-run the same comparison on real data once a Smile instance is available: POC CLI on an NDJSON
export vs. this interceptor on the same database, rows compared as a multiset. The POC's last
run is the yardstick — 668 rows, 29 of 58 columns populated, `Ecdsaltid_01` 668/668,
`ProviderKey` 502/668, 1,114 audit entries (656 `HCFAPOS_00`, 298 `DOS_FROM_ENCOUNTER`,
160 `PROVIDER_KEY_MISSING`).

## Deploy

1. Copy `target/ecds-interceptor-1.0-SNAPSHOT.jar` into Smile's `customerlib/` folder on every
   node that runs batch jobs.
2. Register `org.horizon.cdr.EcdsBulkExportInterceptor` as an interceptor bean type on the
   **FHIR Storage (persistence) module** — the bulk export job runs there and `DaoRegistry` is
   injected from there. Not the endpoint module.
3. Environment (all optional; system property first, then environment variable):

   | Variable | Default | Meaning |
   |---|---|---|
   | `ECDS_OUTPUT_FORMAT` | `text/csv` | the `_outputFormat` that selects ECDS |
   | `ECDS_FALLBACK_FEED` | `athena` | `athena` or `konza`, for records with no data-origin tag |
   | `ECDS_MAX_FILE_BYTES` | 104857600 | close a CSV and start another at this size; clamped to 512 MiB |
   | `ECDS_PAGE_SIZE` | 200 | resources per storage query when walking a member |
   | `ECDS_CACHE_SIZE` | 50000 | resolver cache budget; for resources this is a *byte* budget of this many x 2 KB (~100 MB), not an entry count |
   | `ECDS_AUDIT_TYPE` | `EcdsAudit` | manifest label of the audit file; empty disables it |

   A non-numeric value falls back to the default with a warning. A misspelled
   `ECDS_FALLBACK_FEED` is fatal on purpose: it would otherwise label every row with another
   feed's constants. `ECDS_AUDIT_TYPE=` (empty) really does switch the audit file off —
   "set to empty" and "not set" are distinguished.

   Every resolver cache also has a 10-minute `expireAfterWrite`
   (`EcdsConverterConfig.DEFAULT_CACHE_TTL`). The converter is built once and held for the life
   of the Smile process — sharing its caches across a job's chunks is the point — so without a
   TTL those entries would also survive *between* jobs, and tonight's export would answer a
   Patient lookup, or a "this reference resolves to nothing", from a copy read during last
   night's. Ten minutes is far longer than the gap between two chunks of one job and far shorter
   than the gap between two nightly runs.

4. Requires Smile CDR 2026.11 (HAPI 8.14) and bulk export job definition v3 (the default).

### Sizing

A chunk is converted entirely in memory — every file it produces is held as bytes until the call
returns, because the converter hands back all of them at once. On the POC's data that is about
**134 rows and 215 bytes per member**, so a default 1,000-Patient chunk is roughly 29 MB of CSV
plus a comparable audit file, and several chunks run at once. Raising `ECDS_MAX_FILE_BYTES`
does not help — splitting a chunk's output into more files does not make the chunk smaller.
The lever is **`BulkExportFileMaximumCapacity`**: lower it (say 200) and every chunk, and the
peak heap, shrinks with it.

## Test against a local Smile

### 1. A server that actually has the pointcut

`STORAGE_BULK_EXPORT_RESOURCE_CONVERT` arrived in HAPI 8.13; Smile CDR **2026.11** is the first
release carrying it. Two ways to get one:

- **Smile CDR** — the real target. The distribution is not public; get `smilecdr-2026.11.*` from
  Smile (Niks). Unpack, then `bin/smilecdr start`. Admin console `http://localhost:9100`
  (`admin`/`password`), FHIR endpoint on `:8000` — which is why every command here and in Niks's
  curl uses port 8000. Needs OpenJDK 21 or 17 and ~4 GB RAM; **Smile is not supported on
  Windows**, so use the Docker distribution there — `docker login https://docker.smilecdr.com`
  then pull `docker.smilecdr.com/smilecdr:<release>`. There is no Smile image on Docker Hub;
  `smilecdr/smilecdr` does not exist and a pull of it just 404s.
- **`hapi-fhir-jpaserver-starter`** — free, but as of today its master is on HAPI **8.12.0**,
  which predates the pointcut, so it does *not* work out of the box. It is a plain Spring Boot
  app, so bumping its parent to the same `hapi.version` this project builds against and
  rebuilding does work. Worth it only if a Smile licence is the thing blocking you.

### 2. Install the interceptor

```bash
mvn package
cp target/ecds-interceptor-1.0-SNAPSHOT.jar /path/to/smilecdr/customerlib/
```

Then in `classes/cdr-config-Master.properties`, on the **persistence** module — not the endpoint
module, because that is where the batch job runs and where `DaoRegistry` comes from:

```properties
module.persistence.config.interceptor_bean_types=org.horizon.cdr.EcdsBulkExportInterceptor
```

`bin/smilecdr restart`. Smile only reads `customerlib/` and this property at startup.

Smile's docs say not to bundle library dependencies in a `customerlib` jar. This jar does bundle
three — `commons-csv`, `commons-io`, `commons-codec` — but **relocated** into
`org.horizon.shaded`, which is what that guidance is protecting against: nothing on Smile's own
classpath can collide with a package only this jar names. See the shading note under Build.

### 3. Confirm it registered, before trusting any output

HAPI's own validator gives you this check for free. `BulkExportJobParametersValidator` asks
whether any hook answers for the requested `_outputFormat`, so the two cases are
distinguishable from one request:

```bash
curl -i -u admin:password -H 'Prefer: respond-async' \
  'http://localhost:8000/$export?_outputFormat=text/csv&_type=Patient'
```

- **`202 Accepted`** with a `Content-Location` polling URL — the hook is registered and answering.
- **`Unsupported output format; no known converter available for mime-type text/csv`** — the jar
  is not on the classpath, or `interceptor_bean_types` names the wrong class or the wrong module,
  or Smile was not restarted. The export itself is fine; nothing reached the interceptor.

Startup also logs `ECDS bulk-export converter ready for _outputFormat=text/csv (fallback feed
athena)` the first time the hook is consulted.

### 4. Seed a fixture whose answer is already known, then diff

```bash
python3 tools/seed_local.py    --base http://localhost:8000      # 24 resources, PUT by id
python3 tools/run_ecds_export.py --base http://localhost:8000 --out ./ecds-run
python3 tools/compare_export.py ./ecds-run                       # expect: 16 rows, OK
```

`seed_local.py` loads `src/test/resources/mock-export/*.ndjson` plus
`mock-export-composition.json` — the same fixture
`EcdsResourceConverterTest.patientDrivenExportProducesTheSameRowsAsTheInMemoryMapping` runs on,
read from the same files, so the two cannot drift. `tools/expected-mock-export.csv` is the POC's
in-memory mapping over those resources, and `compare_export.py` diffs the exported rows against
it as a multiset (row order is not part of the contract).

That makes it an end-to-end test with a real oracle: a pass means the pointcut fired, the
patient compartment fan-out found all 16 records through live storage, the document lookup
populated `Ecdsaltid_01` from a *nested* Composition section — the bug that motivated
`DaoDocumentResolver`'s rewrite — and the CSV came back byte-identical to the reference mapping.

A single feed, as Horizon runs it:

```bash
python3 tools/run_ecds_export.py --base http://localhost:8000 --type-filter 'Patient?_tag=KonzaHealth'
```

Output: `Patient-001.csv ...` (the data), `EcdsAudit-001.csv ...` (why columns are blank),
`manifest.json`. Concatenate the `Patient-*.csv` files (drop headers after the first) if Horizon
wants one file. Anything named after another resource type is a header-only placeholder from a
chunk the converter skipped — see the `_type=Patient` note at the top.

### 5. Settings worth changing on a laptop

```properties
module.persistence.config.bulk_export.file_max_capacity=200
```

Smile's own documentation for this property says "the complete contents of individual files are
held in memory while they are being created, so large values may require additional RAM" — the
same arithmetic as Sizing above, from the other direction. 1,000 Patients per chunk is ~29 MB of
CSV plus a comparable audit file, several chunks at once. 200 keeps a laptop comfortable. Raise
the heap in `bin/setenv` if you want the default.

### 6. Volume, with Synthea

Raw Synthea cannot produce a valid ECDS row. Four columns the spec marks Required=Yes have no
source in it at all:

| Column | Comes from | Synthea |
|---|---|---|
| `MemberKey` | `Patient.identifier[system=…/horizon-id]` | no Horizon ids |
| `MemberID` | `Patient.identifier[system=…/card-id]` | no Horizon ids |
| `Ecdsaltid_01` | `Composition.identifier.value` | exports no Composition |
| `ClientSystemOfRecordID`, `Datasourcename` | `meta.tag[system=…/data-origin]` | sets no `meta.tag` |

`tools/synthea_to_horizon.py` adds exactly those four and nothing else, which turns Synthea from
a plumbing test into a real volume test. It is still not an equivalence test — there is no
expected output to diff against, so §4 remains the correctness check.

**Synthea does cover one thing the mock fixture and Horizon's own data cannot.** `HCFAPOS_00` is
Required=Yes and blank on all 668 rows of the POC run, because every `Encounter.class` in
Horizon's feed is data-absent. Synthea populates `Encounter.class` properly and the crosswalk
already covers what it emits (`AMB`→11, `EMER`→23, `IMP`→21, `ACUTE`→21, `SS`→24, `HH`→12,
`VR`→02, `OBSENC`→22), so this is the only way to see that path produce values today.

#### Fastest smoke test — no Synthea install

Smile ships a pre-generated Synthea R4 dataset:

```bash
bin/smileutil upload-sample-dataset -e 'http://localhost:8000/'
```

Good for confirming the server ingests and `$export` runs. It carries no Horizon ids, tags or
Compositions, so expect blank `MemberKey` and `Ecdsaltid_01` — that is the raw-Synthea shape, not
a mapper bug.

#### Generate

```bash
curl -LO https://github.com/synthetichealth/synthea/releases/download/v4.0.0/synthea-with-dependencies.jar

java -jar synthea-with-dependencies.jar \
  -p 300 -s 20261008 -cs 20261008 -a 18-85 \
  --exporter.baseDirectory ./synthea-out \
  --exporter.years_of_history 3
```

Needs JDK 17+. R4 is the default and only enabled FHIR output — there is no `use_r4` flag;
`exporter.fhir.export=true` *is* the R4 exporter. `exporter.fhir.transaction_bundle` is already
`true`, which is what you want. `-s`/`-cs` make the run reproducible.

`--exporter.years_of_history 3` is the main lever on volume, but it is **sub-proportional** —
currently-active conditions and medications are exported regardless of the setting. Start at 300
patients on the default H2 database; H2 is the throughput ceiling, not Smile.

Two things that will confuse you otherwise:

- Patient bundles **omit** Organization, Practitioner and Location, pointing at them with
  conditional references instead (`Practitioner?identifier=http://hl7.org/fhir/sid/us-npi|…`).
  That is why `hospitalInformation*.json` and `practitionerInformation*.json` must load **first**,
  and why Smile needs `allow_inline_match_url_references` on (below). Load them out of order and
  `ProviderKey` and `ProviderType_*` come out empty.
- Those two supporting files are `Bundle.type = batch`, not `transaction`, even with
  `transaction_bundle=true`. Don't "fix" that.
- `--exporter.pretty_print=false` is silently ignored for R4 patient bundles; the parser is
  hardcoded to pretty on that path. Don't bother passing it.

#### Reshape, then load

```bash
python3 tools/synthea_to_horizon.py --in ./synthea-out/fhir --out ./synthea-horizon

bin/smileutil synchronize-fhir-servers \
  -v r4 -m SYNTHEA \
  --source /abs/path/to/synthea-horizon \
  --target http://localhost:8000 \
  --thread-count 10 --target-retry-count 2
```

`-m SYNTHEA` is a first-class Synthea loader: it treats each file as a transaction bundle, loads
the two supporting bundles before the patient bundles, and threads the rest. **Pass
`--target-retry-count`** — the default is no retries at all, and a long run on a laptop will drop
bundles to transient contention and leave you with a quietly incomplete dataset. SYNTHEA mode
takes a **bare path**, not the `file:///` URL that COPY mode wants.

`synthea_to_horizon.py` preserves filenames, so `smileutil` still recognises the supporting
bundles in the transformed directory. It writes the synthesised Composition into the *same*
transaction bundle using Synthea's own `urn:uuid:` references, so Smile resolves everything in
one pass. Some entries go into a **nested** section deliberately — the R4 `Composition-entry`
search parameter indexes top-level sections only, and a volume run should exercise the recursive
walk rather than the easy path.

Add `--base http://localhost:8000` instead of `--out` to POST directly and skip `smileutil`,
but then you lose the ordering and retries it gives you.

#### Settings for the load

```properties
module.persistence.config.dao_config.allow_inline_match_url_references.enabled=true
module.persistence.config.dao_config.auto_create_placeholder_reference_targets.enabled=true
module.persistence.config.dao_config.mass_ingestion_mode=true
```

The first is required for Synthea's conditional references to resolve. Heap, in `bin/setenv` —
append, do not replace, or you discard what Smile already put there:

```bash
JVMARGS="$JVMARGS -Xmx6g"
```

> **Do not apply Smile's `search_parameter_seeding.disable_patterns=*` bulk-load recipe to this
> project.** Enable patterns act as a global whitelist, and this interceptor is built entirely on
> patient-compartment fan-out plus a Composition lookup. Disable the wrong search parameters and
> `$export` returns rows with empty clinical columns — indistinguishable from a mapper
> regression, on the one run whose purpose is proving the fan-out finds everything. Leave search
> parameter seeding at its default here.

Same reasoning for `enforce_referential_integrity_on_write.enabled=false`: it does not skip
indexing, so it buys little, and it converts dangling references into silent under-counts.

#### Known Synthea-on-Smile defect

The `practitionerInformation` bundle can fail with `Unable to satisfy placeholder ID urn:uuid:…
found in element named 'practitioner'` on `PractitionerRole`. It matters here specifically —
`ProviderKey` and `ProviderType_00..05` come off `Practitioner`, and the POC yardstick is
`ProviderKey` 502/668. `auto_create_placeholder_reference_targets` plus
`allow_inline_match_url_references` is the documented mitigation; patient bundles will load
without the practitioner bundle, with provider columns degraded. Also note `PractitionerRole`
entries carry no `ifNoneExist` precondition (unlike `Organization`, `Location` and
`Practitioner`), so re-loading the supporting bundles duplicates them.

#### Then run it

```bash
python3 tools/run_ecds_export.py --base http://localhost:8000 --out ./ecds-synthea-run
```

Don't run `compare_export.py` on this — there is no oracle for Synthea data. Read the
`EcdsAudit-*.csv` files instead: that is where a volume run tells you something. Counts of
`PROVIDER_KEY_MISSING`, `DOS_FROM_ENCOUNTER` and `REQUIRED_FIELD_MISSING` against 300 patients'
worth of records are the signal, and the startup log's untagged-record count should be zero
because the transform tags everything.

The comparison still waiting on real data: POC CLI over an NDJSON export vs. this interceptor
over the same database, rows compared as a multiset. The POC's last run is the yardstick —
668 rows, 29 of 58 columns populated, `Ecdsaltid_01` 668/668, `ProviderKey` 502/668, 1,114 audit
entries.

## Decisions carried over from the POC

No terminology uplift; the four-step provider walk with the document attester excluded;
`Ecdsaltid_01` is the bare `Composition.identifier.value`; `ProviderType_00..05` de-duplicated on
type with unmapped taxonomies blank and audited; null-flavour birthsex treated as absent;
a coded element marked data-absent still counts if it carries a coding.

## Deliberate departures from the POC

Each follows the mapping spec where the POC did not. The first two can only fill a column the
POC left blank; the third can change a value it produced, so verify that one against the
reference output before shipping.

- **`MedicationDispense` produces rows.** The mapping makes it a first-class row source — the
  stated path for `QuantityDispensed`, one of two sources for `RxNorm`, and one of the performers
  `ProviderKey` is "reached from" — and Niks's export pulls it. `CodeColumns`, `DateOfService`
  and `ProviderColumns` already handled it; only the row-producing list was missing it. The POC
  export contained none, so none of its 668 rows change. **Confirm this is wanted** before the
  first real run.
- **`medicationReference` resolves to a Medication of its own**, not only a contained one. The
  mapping says "contained *or referenced*"; the CLI had no lookup to hand `CodeColumns`, so a
  `MedicationRequest` written as `medicationReference: Medication/123` lost its RxNorm code.
  Inline `medicationCodeableConcept` still wins.
- **`RxProviderFlag` reads the prescriber, not the visit.** This one *can* change a value the
  POC produced, so check it first. The flag was taken from the same four-step walk that fills
  `ProviderKey`, which falls back to the encounter's participant and then to the source
  document's encounter — so it reported 1 when somebody on the visit had an NPI and the
  prescriber had none. The spec names a single source: "`MedicationRequest.requester` resolving
  to `Practitioner.identifier` where system='http://hl7.org/fhir/sid/us-npi'". It now reads only
  the performer path declared for the record's own type (`requester` for a MedicationRequest,
  `performer.actor` for a MedicationDispense). In the POC's output 39 of 59 MedicationRequest
  rows carry a 1; any of those whose NPI came from the encounter rather than the requester will
  now be 0. `ProviderKey` is unchanged and still walks out to the visit.

## Open items

- **Smile CDR 2026.11 in the Horizon environment** — the pointcut does not exist before it.
- **S3 and scheduling** — tabled by Niks; whatever runs `tools/run_ecds_export.py`'s steps on a
  schedule also does the upload. The manifest's `EcdsAudit` entries are the error file.
- **Partitioning.** Every storage read runs as `SystemRequestDetails.forAllPartitions()`, while
  the export job itself scopes its reads to each resource's own partition. On an unpartitioned
  instance — which Horizon's appears to be, since the two feeds are distinguished by `_tag` and
  not by partition — this is a no-op. On a partitioned one it would read across tenants. The fix
  if that changes: take the partition off each Patient with
  `RequestPartitionId.getPartitionFromUserDataIfPresent(resource)` (the JPA layer sets it when
  the resource is read), scope the member's fan-out and resolver reads to it, and include it in
  the resolver cache keys so one partition's answers cannot serve another's.
- **Export filters do not reach the fan-out.** HAPI applies `getPostFetchFilterUrls()` and then
  the `STORAGE_BULK_EXPORT_RESOURCE_INCLUSION` hook to each chunk *before* calling `consume()`,
  so the Patients handed to the converter are filtered correctly. The clinical records are not
  in any chunk — `PatientCompartmentSource` reads them straight from the DAO — so neither
  filter applies to them. A `_typeFilter` on `Condition`, or an inclusion hook suppressing a
  withdrawn member's records, would be honoured for `Patient` and silently ignored for
  everything else. This is inherent to the patient-driven design rather than a defect in it:
  any fan-out that queries storage directly sidesteps a filter applied to the chunk pipeline.
  Horizon's own tooling only filters on `Patient` (`--type-filter 'Patient?_tag=KonzaHealth'`),
  which works. The fix when more is needed: inject `InMemoryResourceMatcher` and match each
  fetched record against `getPostFetchFilterUrls()`, and/or inject `IInterceptorBroadcaster` and
  call the inclusion pointcut per record.
- **`_since` semantics** — in the patient-driven export, `_since` selects *patients* updated
  since then; a member's full record set is always exported. Fine for full runs; incremental
  runs need a decision from Horizon on what "changed" means.
- **A record with no `subject`/`patient` never produces a row.** The CLI emitted one with the
  member columns blank and a `MEMBER_KEY_MISSING` audit entry; the fan-out cannot reach a record
  that is in no patient compartment, and it is not counted anywhere. The POC export had none
  (`MemberKey` was populated on all 668 rows), so there is nothing to compare against yet.
- **`HCFAPOS_00`** is required and blank on every row (every `Encounter.class` is data-absent);
  needs Horizon's answer, not code.
- **`PractitionerRole.specialty`** — the spec's primary taxonomy source; now one search away via
  the resolver. Not implemented because no data carried it. Niks's export pulls `PractitionerRole`,
  so the data to test it against may be arriving.
- **NPPES Registry lookup** for `ProviderTaxonomy` where the source carries no discrete taxonomy.
  Still a question of whether this should make network calls at all.
- One known difference from the POC's index, to confirm on real data: the index's one-hop
  "outward" trace (a resource listed in a document pulls in what it references) is not
  reproduced; it only reached non-row types in the POC export.
- `tools/run_ecds_export.py` takes `--password` on the command line, which the POC deliberately
  stopped doing (argv is visible in `ps` and lands in shell history). Fine for a local Synthea
  instance, not for anything real — use `--auth-header`.
