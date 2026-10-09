package org.horizon.ecds;

import com.fasterxml.jackson.databind.JsonNode;
import org.horizon.fhir.DocumentResolver;
import org.horizon.fhir.FhirPaths;
import org.horizon.fhir.ResourceResolver;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns one FHIR record into its ECDS rows.
 *
 * <p>Lifted from the POC's {@code EcdsMapper}, minus everything that knew about NDJSON: it depends
 * on nothing but two resolver interfaces. Tests hand it the in-memory indexes; the interceptor
 * hands it resolvers backed by the CDR's own storage. The column logic is the POC's, unchanged.
 *
 * <p>Only clinical events become rows. Patient and Practitioner supply columns to other rows
 * rather than producing their own, and ServiceRequest is listed in the spec purely as a
 * CPT/HCPCS source.
 */
public final class EcdsRowBuilder {

    /**
     * The types that produce rows.
     *
     * <p>{@code MedicationDispense} is here because the mapping treats it as a first-class row
     * source — it is the stated path for {@code QuantityDispensed}
     * ("use {@code MedicationDispense.quantity.value} where present"), one of the two sources
     * for {@code RxNorm}, and one of the performers {@code ProviderKey} is "reached from" — and
     * because the reference {@code $export} pulls it. {@link CodeColumns}, {@link DateOfService} and
     * {@link ProviderColumns} already handle it. The POC export contained none, so no row of its
     * reference output changes; the first Horizon dataset that carries one will produce rows
     * here that the POC would have dropped. Confirm that is wanted before the first real run.
     */
    private static final List<String> ROW_PRODUCING_TYPES_IN_ORDER = List.of(
            "Encounter",
            "Condition",
            "Observation",
            "Procedure",
            "DiagnosticReport",
            "Immunization",
            "MedicationRequest",
            "MedicationDispense");

    private static final Set<String> ROW_PRODUCING_TYPES = Set.copyOf(ROW_PRODUCING_TYPES_IN_ORDER);

    private final FeedConstants myFallbackFeed;
    private final ResourceResolver myResolver;
    private final DocumentResolver myDocuments;
    private final AuditLog myAudit;
    private final Map<String, Long> myOriginCounts = new TreeMap<>();
    private long myUntaggedRecords;

    /**
     * @param theFallbackFeed used only for records that carry no recognised data-origin tag
     * @param theResolver     resolves Patient, Practitioner, Encounter and Organization references
     * @param theDocuments    finds the source document a record arrived in
     * @param theAudit        receives one entry per blank column that the row ships without
     */
    public EcdsRowBuilder(FeedConstants theFallbackFeed, ResourceResolver theResolver,
                          DocumentResolver theDocuments, AuditLog theAudit) {
        myFallbackFeed = theFallbackFeed;
        myResolver = theResolver;
        myDocuments = theDocuments;
        myAudit = theAudit == null ? AuditLog.discarding() : theAudit;
    }

    /** The resource types a source has to supply; everything else is streamed past. */
    public static Set<String> rowProducingTypes() {
        return ROW_PRODUCING_TYPES;
    }

    /**
     * The same types in a fixed order — the order a member's records are walked in the
     * patient-driven export, so two runs over unchanged data write the same rows in the same
     * sequence.
     */
    public static List<String> rowProducingTypesInOrder() {
        return ROW_PRODUCING_TYPES_IN_ORDER;
    }

    public static boolean producesRows(String theResourceType) {
        return ROW_PRODUCING_TYPES.contains(theResourceType);
    }

    /**
     * @return zero rows for a record of a non-row type or a suppressed record, otherwise one or
     *         more — never null
     */
    public List<EcdsRow> build(JsonNode theRecord) {
        if (theRecord == null || !producesRows(theRecord.path("resourceType").asText())) {
            return List.of();
        }
        // The columns every row from this record shares, built once and copied per variant.
        EcdsRow base = new EcdsRow();
        feedFor(theRecord).applyTo(base);
        patientColumnsFor(theRecord).applyTo(base);
        applyDates(theRecord, base);
        CodeColumns.applyTo(theRecord, base, myResolver);
        ProviderColumns.applyTo(theRecord, myResolver, myDocuments, base, myAudit);
        base.set(EcdsColumn.Ecdsaltid_00, traceabilityId(theRecord));
        // The CCD this resource arrived in, listed directly or reached through a referrer.
        base.set(EcdsColumn.Ecdsaltid_01, myDocuments.documentFor(theRecord)
                .map(DocumentResolver.SourceDocument::identifier)
                .orElse(""));

        return RowExploder.explode(theRecord, base);
    }

    /** How many records came from each data origin, for the run summary. */
    public Map<String, Long> originCounts() {
        return Map.copyOf(myOriginCounts);
    }

    /** Records that carried no recognised origin tag and fell back to the configured feed. */
    public long untaggedRecords() {
        return myUntaggedRecords;
    }

    private void applyDates(JsonNode theRecord, EcdsRow theRow) {
        String stated = DateOfService.of(theRecord);
        String dos = stated.isEmpty() ? DateOfService.of(theRecord, myResolver) : stated;
        theRow.set(EcdsColumn.DOS, dos);
        theRow.set(EcdsColumn.DOSThru, DateOfService.throughOf(theRecord, dos, myResolver));

        if (dos.isEmpty()) {
            myAudit.warn(AuditCode.DOS_MISSING, traceabilityId(theRecord), EcdsColumn.DOS,
                    "no date element for " + theRecord.path("resourceType").asText());
        } else if (stated.isEmpty()) {
            // The date is the encounter's, not the record's. Flagged so an inferred service
            // date is never mistaken for one the source stated.
            myAudit.warn(AuditCode.DOS_FROM_ENCOUNTER, traceabilityId(theRecord), EcdsColumn.DOS,
                    "no date on the record; used its encounter's " + dos);
        }
    }

    /**
     * Per-record traceability back to the source FHIR resource. ReferenceID is a fixed
     * constant, so this is what lets Horizon tie a row to what produced it.
     */
    private String traceabilityId(JsonNode theRecord) {
        String type = theRecord.path("resourceType").asText();
        return FhirPaths.primitive(theRecord, "id")
                .map(id -> type + "/" + id)
                .orElse("");
    }

    /**
     * The feed this record came from, taken from its {@code meta.data-origin} tag.
     *
     * <p>The tag is authoritative: Smile CDR stamps it per resource, so one run can mix origins
     * and each row carries its own. The configured feed is only a fallback for untagged records.
     */
    private FeedConstants feedFor(JsonNode theRecord) {
        DataOrigin.originCode(theRecord).ifPresentOrElse(
                code -> myOriginCounts.merge(code, 1L, Long::sum),
                () -> myUntaggedRecords++);

        FeedConstants tagged = DataOrigin.of(theRecord);
        return tagged != null ? tagged : myFallbackFeed;
    }

    /**
     * The member columns for whichever Patient this record is about. A record whose Patient
     * cannot be resolved still ships, with those columns blank, rather than being dropped.
     */
    private PatientColumns patientColumnsFor(JsonNode theRecord) {
        Optional<String> reference = FhirPaths.reference(theRecord.get("subject"))
                .or(() -> FhirPaths.reference(theRecord.get("patient")));
        Optional<JsonNode> patient = reference.flatMap(value -> myResolver.resolve(value, theRecord));
        if (patient.isEmpty()) {
            myAudit.warn(AuditCode.MEMBER_KEY_MISSING, traceabilityId(theRecord), EcdsColumn.MemberKey,
                    reference.map(value -> "unresolved " + value).orElse("no subject reference"));
            return PatientColumns.absent();
        }
        return PatientColumns.from(patient.get());
    }
}
