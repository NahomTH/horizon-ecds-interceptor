package org.horizon.ecds;

import com.fasterxml.jackson.databind.JsonNode;
import org.horizon.fhir.FhirPaths;
import org.horizon.fhir.DocumentResolver;
import org.horizon.fhir.ResourceResolver;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The provider columns, plus the pharmacy and place-of-service columns that depend on the same
 * resolved references.
 */
public final class ProviderColumns {

    private static final String NPI_SYSTEM = "http://hl7.org/fhir/sid/us-npi";
    private static final String NUCC_SYSTEM = "http://nucc.org/provider-taxonomy";

    /** Employer Identification Number — the Organization TIN the mapping falls back to. */
    private static final String TIN_SYSTEM = "urn:oid:2.16.840.1.113883.4.4";

    /** Where each resource type carries the performer of the event that produced the row. */
    private static final Map<String, List<String>> PERFORMER_PATHS = Map.of(
            "Observation", List.of("performer"),
            "Immunization", List.of("performer.actor"),
            "Procedure", List.of("performer.actor"),
            "Encounter", List.of("participant.individual"),
            "MedicationRequest", List.of("requester"),
            "MedicationDispense", List.of("performer.actor"),
            "DiagnosticReport", List.of("performer"));

    /** Smile's approved crosswalk from Encounter.class to the CMS place-of-service code. */
    private static final Map<String, String> PLACE_OF_SERVICE = Map.of(
            "AMB", "11",
            "EMER", "23",
            "IMP", "21",
            "ACUTE", "21",
            "SS", "24",
            "HH", "12",
            "VR", "02",
            "OBSENC", "22");

    /** ProviderType_00 is the first; the rest fill upward from it. */
    private static final List<EcdsColumn> PROVIDER_TYPE_COLUMNS = List.of(
            EcdsColumn.ProviderType_00, EcdsColumn.ProviderType_01, EcdsColumn.ProviderType_02,
            EcdsColumn.ProviderType_03, EcdsColumn.ProviderType_04, EcdsColumn.ProviderType_05);

    private ProviderColumns() {
    }

    public static void applyTo(JsonNode theRecord, ResourceResolver theIndex,
                               DocumentResolver theDocuments, EcdsRow theRow, AuditLog theAudit) {
        if (theRecord == null) {
            return;
        }
        String resourceType = theRecord.path("resourceType").asText();
        Optional<JsonNode> performer = resolvePerformer(theRecord, resourceType, theIndex, theDocuments);

        applyProviderKey(theRecord, resourceType, performer, theRow, theAudit);
        applyTaxonomyColumns(theRecord, resourceType, performer, theRow, theAudit);

        // Required on every row, and a row with no prescriber NPI to find is a 0 rather than
        // a blank — including a row that is not a medication at all.
        //
        // Deliberately NOT read off `performer`: that is the four-step ProviderKey walk, which
        // falls back to the encounter's participant and then to the document's encounter. The
        // spec names one source for this flag — "MedicationRequest.requester resolving to
        // Practitioner.identifier where system='http://hl7.org/fhir/sid/us-npi'" — so a 1 has to
        // mean the prescriber had an NPI, not that somebody on the visit did.
        theRow.set(EcdsColumn.RxProviderFlag, prescriberNpi(theRecord, resourceType, theIndex) ? "1" : "0");

        if (isMedication(resourceType)) {
            theRow.set(EcdsColumn.QuantityDispensed, quantityDispensed(theRecord));
        }
        // Required on every row, so a clinical record takes the place of service of the
        // visit it belongs to rather than leaving the column to Encounter rows alone.
        Optional<String> placeOfService = encounterOf(theRecord, resourceType, theIndex)
                .flatMap(ProviderColumns::placeOfService);
        placeOfService.ifPresent(pos -> theRow.set(EcdsColumn.HCFAPOS_00, pos));
        if (placeOfService.isEmpty()) {
            // Nothing in this export can supply it: every Encounter.class is data-absent, as
            // are the Location types. Reported rather than filled with a placeholder that
            // would assert a place of service the source never stated.
            theAudit.warn(AuditCode.REQUIRED_FIELD_MISSING,
                    resourceType + "/" + theRecord.path("id").asText(),
                    EcdsColumn.HCFAPOS_00,
                    "no Encounter.class or Location.type to derive a place of service from");
        }
    }

    /**
     * NPI where the performer has one, else the Organization TIN, else blank and flagged.
     *
     * <p>The value ships verbatim with no format validation: blank means the identifier is
     * absent, not that it looked wrong. Horizon validates on ingest.
     */
    private static void applyProviderKey(JsonNode theRecord, String theResourceType,
                                         Optional<JsonNode> thePerformer, EcdsRow theRow,
                                         AuditLog theAudit) {
        Optional<String> key = thePerformer.flatMap(ProviderColumns::npiOf)
                .or(() -> thePerformer.flatMap(ProviderColumns::taxIdOf));
        if (key.isPresent()) {
            theRow.set(EcdsColumn.ProviderKey, key.get());
            return;
        }
        theAudit.warn(AuditCode.PROVIDER_KEY_MISSING,
                theResourceType + "/" + theRecord.path("id").asText(),
                EcdsColumn.ProviderKey,
                thePerformer.isPresent() ? "performer carries no NPI or TIN" : "no performer on the record");
    }

    /**
     * The practitioner responsible for the event behind this row.
     *
     * <p>Most clinical records in a CCD-derived export name no performer of their own — the
     * provider is recorded once on the encounter instead. So where the record has no usable
     * performer, the encounter it belongs to is asked for a participant carrying an NPI.
     */
    private static Optional<JsonNode> resolvePerformer(JsonNode theRecord, String theResourceType,
                                                       ResourceResolver theIndex,
                                                       DocumentResolver theDocuments) {
        for (String path : PERFORMER_PATHS.getOrDefault(theResourceType, List.of())) {
            Optional<JsonNode> resolved = referenceAt(theRecord, path)
                    .flatMap(reference -> theIndex.resolve(reference, theRecord));
            if (resolved.filter(performer -> npiOf(performer).isPresent()).isPresent()) {
                return resolved;
            }
        }
        return encounterParticipant(theRecord, theResourceType, theIndex)
                // Some records name no encounter at all; the document that carried them
                // describes one visit, and that is where the provider was recorded. The
                // document's attester is deliberately not used: signing a document is not a
                // claim about who performed the event.
                .or(() -> documentEncounterParticipant(theRecord, theResourceType, theIndex, theDocuments))
                // Nothing on either encounter: fall back to whatever the record named,
                // so an Organization TIN can still be reached.
                .or(() -> firstNamedPerformer(theRecord, theResourceType, theIndex));
    }

    /**
     * Whether the prescriber named on the medication entry itself carries an NPI.
     *
     * <p>Only the performer paths declared for the record's own type are consulted — for a
     * MedicationRequest that is {@code requester}, for a MedicationDispense
     * {@code performer.actor}. No encounter and no document fallback: those answer "who was on
     * the visit", which is the right question for {@code ProviderKey} and the wrong one here.
     */
    private static boolean prescriberNpi(JsonNode theRecord, String theResourceType,
                                         ResourceResolver theIndex) {
        if (!isMedication(theResourceType)) {
            return false;
        }
        for (String path : PERFORMER_PATHS.getOrDefault(theResourceType, List.of())) {
            boolean hasNpi = referenceAt(theRecord, path)
                    .flatMap(reference -> theIndex.resolve(reference, theRecord))
                    .flatMap(ProviderColumns::npiOf)
                    .isPresent();
            if (hasNpi) {
                return true;
            }
        }
        return false;
    }

    private static Optional<JsonNode> documentEncounterParticipant(JsonNode theRecord,
                                                                   String theResourceType,
                                                                   ResourceResolver theIndex,
                                                                   DocumentResolver theDocuments) {
        if (theDocuments == null) {
            return Optional.empty();
        }
        return theDocuments.documentFor(theRecord)
                .flatMap(DocumentResolver.SourceDocument::encounter)
                .flatMap(encounter -> theIndex.resolve(encounter, theRecord))
                .flatMap(encounter -> firstParticipantWithNpi(encounter, theIndex));
    }

    /** The first participant on the record's encounter that carries an NPI. */
    private static Optional<JsonNode> encounterParticipant(JsonNode theRecord, String theResourceType,
                                                           ResourceResolver theIndex) {
        return encounterOf(theRecord, theResourceType, theIndex)
                .flatMap(found -> firstParticipantWithNpi(found, theIndex));
    }

    /** The encounter this record belongs to, or the record itself when it is one. */
    private static Optional<JsonNode> encounterOf(JsonNode theRecord, String theResourceType,
                                                  ResourceResolver theIndex) {
        if ("Encounter".equals(theResourceType)) {
            return Optional.of(theRecord);
        }
        return FhirPaths.reference(theRecord.get("encounter"))
                .flatMap(reference -> theIndex.resolve(reference, theRecord));
    }

    private static Optional<JsonNode> firstParticipantWithNpi(JsonNode theEncounter,
                                                              ResourceResolver theIndex) {
        return FhirPaths.array(theEncounter, "participant")
                .map(participant -> FhirPaths.reference(participant.get("individual"))
                        .flatMap(reference -> theIndex.resolve(reference, theEncounter)))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(practitioner -> npiOf(practitioner).isPresent())
                .findFirst();
    }

    private static Optional<JsonNode> firstNamedPerformer(JsonNode theRecord, String theResourceType,
                                                          ResourceResolver theIndex) {
        for (String path : PERFORMER_PATHS.getOrDefault(theResourceType, List.of())) {
            Optional<JsonNode> resolved = referenceAt(theRecord, path)
                    .flatMap(reference -> theIndex.resolve(reference, theRecord));
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return Optional.empty();
    }

    /** Reads {@code field} or {@code field.child}, whether the field is an array or an object. */
    private static Optional<String> referenceAt(JsonNode theRecord, String thePath) {
        int dot = thePath.indexOf('.');
        String field = dot < 0 ? thePath : thePath.substring(0, dot);
        JsonNode node = theRecord.get(field);
        if (node == null) {
            return Optional.empty();
        }
        JsonNode first = node.isArray() ? (node.isEmpty() ? null : node.get(0)) : node;
        if (first == null) {
            return Optional.empty();
        }
        JsonNode target = dot < 0 ? first : first.get(thePath.substring(dot + 1));
        return FhirPaths.reference(target);
    }

    private static Optional<String> npiOf(JsonNode thePractitioner) {
        return identifierBySystem(thePractitioner, NPI_SYSTEM);
    }

    private static Optional<String> taxIdOf(JsonNode theOrganization) {
        return identifierBySystem(theOrganization, TIN_SYSTEM);
    }

    private static Optional<String> identifierBySystem(JsonNode theResource, String theSystem) {
        return FhirPaths.array(theResource, "identifier")
                .filter(identifier -> FhirPaths.primitive(identifier, "system")
                        .filter(theSystem::equals)
                        .isPresent())
                .map(identifier -> FhirPaths.primitive(identifier, "value").orElse(""))
                .filter(value -> !value.isEmpty())
                .findFirst();
    }

    /**
     * {@code ProviderTaxonomy} plus {@code ProviderType_00..05}.
     *
     * <p>The spec anticipates one taxonomy mapping to several ProviderType values, filling the
     * columns upward. Horizon's delivered crosswalk is one-to-one, so in practice the columns
     * fill one per distinct taxonomy the provider carries.
     */
    private static void applyTaxonomyColumns(JsonNode theRecord, String theResourceType,
                                             Optional<JsonNode> thePerformer, EcdsRow theRow,
                                             AuditLog theAudit) {
        List<String> taxonomies = thePerformer
                .map(ProviderColumns::taxonomiesOf)
                .orElseGet(List::of);
        if (taxonomies.isEmpty()) {
            return;
        }
        // The column is singular, so the first code stands for the provider.
        theRow.set(EcdsColumn.ProviderTaxonomy, taxonomies.get(0));

        // De-duplicated on the ProviderType, not the taxonomy: several cardiology taxonomies
        // all crosswalk to CARD, and Horizon's rule is not to repeat the _00 value.
        List<String> providerTypes = new ArrayList<>();
        for (String taxonomy : taxonomies) {
            Optional<String> providerType = ProviderTypeCrosswalk.providerTypeFor(taxonomy);
            if (providerType.isEmpty()) {
                theAudit.warn(AuditCode.PROVIDER_TYPE_UNMAPPED,
                        theResourceType + "/" + theRecord.path("id").asText(),
                        EcdsColumn.ProviderType_00,
                        "no ProviderType for NUCC taxonomy " + taxonomy);
            } else if (!providerTypes.contains(providerType.get())) {
                providerTypes.add(providerType.get());
            }
        }
        for (int i = 0; i < Math.min(providerTypes.size(), PROVIDER_TYPE_COLUMNS.size()); i++) {
            theRow.set(PROVIDER_TYPE_COLUMNS.get(i), providerTypes.get(i));
        }
        if (providerTypes.size() > PROVIDER_TYPE_COLUMNS.size()) {
            theAudit.warn(AuditCode.PROVIDER_TYPE_OVERFLOW,
                    theResourceType + "/" + theRecord.path("id").asText(),
                    EcdsColumn.ProviderType_05,
                    providerTypes.size() + " provider types, only "
                            + PROVIDER_TYPE_COLUMNS.size() + " columns");
        }
    }

    /**
     * Every NUCC code the practitioner carries, in source order, de-duplicated.
     *
     * <p>The spec's other route is an NPPES Registry lookup keyed on the NPI. That is a network
     * call against data this export does not have, so it is deliberately not attempted.
     * {@code PractitionerRole.specialty} is likewise unsupported: no PractitionerRole appears
     * in the export, so the code could not be exercised.
     */
    private static List<String> taxonomiesOf(JsonNode thePractitioner) {
        List<String> taxonomies = new ArrayList<>();
        FhirPaths.array(thePractitioner, "qualification").forEach(qualification ->
                FhirPaths.codes(qualification.get("code"), NUCC_SYSTEM).forEach(code -> {
                    if (!taxonomies.contains(code)) {
                        taxonomies.add(code);
                    }
                }));
        return taxonomies;
    }

    private static boolean isMedication(String theResourceType) {
        return "MedicationRequest".equals(theResourceType)
                || "MedicationDispense".equals(theResourceType);
    }

    /** Defaults to 1: the column must not be zero, and a missing quantity is not meaningful. */
    private static String quantityDispensed(JsonNode theRecord) {
        JsonNode quantity = Optional.ofNullable(theRecord.get("quantity"))
                .orElseGet(() -> {
                    JsonNode dispenseRequest = theRecord.get("dispenseRequest");
                    return dispenseRequest == null ? null : dispenseRequest.get("quantity");
                });
        JsonNode value = quantity == null ? null : quantity.get("value");
        if (value == null || !value.isNumber()) {
            return "1";
        }
        BigDecimal amount = new BigDecimal(value.asText()).stripTrailingZeros();
        return amount.signum() <= 0 ? "1" : amount.toPlainString();
    }

    /**
     * A 2-digit CMS place-of-service code inferred from Encounter.class.
     *
     * <p>An unrecognised class is left blank rather than guessed at. Every Encounter in the
     * current export carries a data-absent class, so this yields nothing today.
     */
    private static Optional<String> placeOfService(JsonNode theEncounter) {
        JsonNode encounterClass = theEncounter.get("class");
        if (FhirPaths.isAbsent(encounterClass)) {
            return Optional.empty();
        }
        return FhirPaths.primitive(encounterClass, "code")
                .map(PLACE_OF_SERVICE::get);
    }
}
