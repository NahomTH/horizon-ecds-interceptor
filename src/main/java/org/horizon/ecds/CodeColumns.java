package org.horizon.ecds;

import com.fasterxml.jackson.databind.JsonNode;
import org.horizon.fhir.FhirPaths;
import org.horizon.fhir.ResourceResolver;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The clinical code columns, derived from whichever resource produced the row.
 *
 * <p>Each column is populated from its own coding in the source. There is no terminology uplift
 * in either direction — Horizon's direction of 2026-09-16 — so a SNOMED-only Condition leaves
 * the ICD columns blank and the SNOMED column carries the concept, and vice versa.
 */
public final class CodeColumns {

    /** ICDDx10_00 is the principal diagnosis; _01.._09 are secondaries. */
    private static final List<EcdsColumn> DIAGNOSIS_COLUMNS = List.of(
            EcdsColumn.ICDDx10_00, EcdsColumn.ICDDx10_01, EcdsColumn.ICDDx10_02,
            EcdsColumn.ICDDx10_03, EcdsColumn.ICDDx10_04, EcdsColumn.ICDDx10_05,
            EcdsColumn.ICDDx10_06, EcdsColumn.ICDDx10_07, EcdsColumn.ICDDx10_08,
            EcdsColumn.ICDDx10_09);

    private CodeColumns() {
    }

    /** Codes that need no lookup outside the record itself. */
    public static void applyTo(JsonNode theRecord, EcdsRow theRow) {
        applyTo(theRecord, theRow, null);
    }

    /**
     * @param theResolver resolves a {@code medicationReference} that points at a Medication
     *                    resource of its own rather than a contained one; may be null, in which
     *                    case only contained Medications are read
     */
    public static void applyTo(JsonNode theRecord, EcdsRow theRow, ResourceResolver theResolver) {
        if (theRecord == null) {
            return;
        }
        JsonNode code = theRecord.get("code");

        switch (theRecord.path("resourceType").asText()) {
            case "Observation" -> applyObservation(theRecord, code, theRow);
            case "Condition" -> applyCondition(code, theRow);
            case "Procedure" -> applyProcedure(code, theRow);
            case "DiagnosticReport" -> applyDiagnosticReport(theRecord, code, theRow);
            case "MedicationRequest", "MedicationDispense" -> applyMedication(theRecord, theRow, theResolver);
            case "Immunization" -> applyImmunization(theRecord, theRow);
            case "Encounter" -> applyEncounter(theRecord, theRow);
            default -> {
                // Nothing else produces rows, so nothing else carries codes.
            }
        }
    }

    private static void applyObservation(JsonNode theRecord, JsonNode theCode, EcdsRow theRow) {
        first(theCode, CodeSystems.LOINC).ifPresent(v -> theRow.set(EcdsColumn.LOINC, v));
        first(theCode, CodeSystems.CPT).ifPresent(v -> theRow.set(EcdsColumn.CPTPx, v));

        JsonNode value = theRecord.get("valueCodeableConcept");
        // The coded answer, not the question: SNOMED and LOINCAnswer both read the value.
        first(value, CodeSystems.SNOMED).ifPresent(v -> theRow.set(EcdsColumn.SNOMED, v));
        first(value, CodeSystems.SNOMED)
                .or(() -> first(value, CodeSystems.LOINC))
                .ifPresent(v -> theRow.set(EcdsColumn.LOINCAnswer, v));
        first(value, CodeSystems.RADLEX).ifPresent(v -> theRow.set(EcdsColumn.RadLex, v));

        numericValue(theRecord.get("valueQuantity")).ifPresent(v -> theRow.set(EcdsColumn.Result, v));
    }

    private static void applyCondition(JsonNode theCode, EcdsRow theRow) {
        first(theCode, CodeSystems.SNOMED).ifPresent(v -> theRow.set(EcdsColumn.SNOMED, v));
        first(theCode, CodeSystems.ICD_9_CM)
                .ifPresent(v -> theRow.set(EcdsColumn.ICDDX, CodeSystems.stripDecimal(v)));
        applyDiagnoses(FhirPaths.codes(theCode, CodeSystems.ICD_10_CM), theRow);
    }

    private static void applyProcedure(JsonNode theCode, EcdsRow theRow) {
        first(theCode, CodeSystems.CPT).ifPresent(v -> theRow.set(EcdsColumn.CPTPx, v));
        first(theCode, CodeSystems.HCPCS)
                .or(() -> first(theCode, CodeSystems.HCPCS_BLUEBUTTON))
                .ifPresent(v -> theRow.set(EcdsColumn.HCPCSPx, v));
        first(theCode, CodeSystems.SNOMED).ifPresent(v -> theRow.set(EcdsColumn.SNOMED, v));
        first(theCode, CodeSystems.ICD_10_PCS)
                .ifPresent(v -> theRow.set(EcdsColumn.ICDPx10, CodeSystems.stripDecimal(v)));
        first(theCode, CodeSystems.LOINC).ifPresent(v -> theRow.set(EcdsColumn.LOINC, v));
    }

    private static void applyDiagnosticReport(JsonNode theRecord, JsonNode theCode, EcdsRow theRow) {
        first(theCode, CodeSystems.LOINC).ifPresent(v -> theRow.set(EcdsColumn.LOINC, v));
        first(theRecord.get("conclusionCode"), CodeSystems.RADLEX)
                .ifPresent(v -> theRow.set(EcdsColumn.RadLex, v));
    }

    /**
     * An Encounter states its CPT in {@code type}, which the spec lists as a CPTPx source.
     *
     * <p>{@code type} is a <em>list</em> of CodeableConcepts, where every other resource here
     * exposes a single {@code code}, so the CPT may sit in any entry rather than the first.
     *
     * <p>Only CPT is taken. Encounter.type also carries SNOMED in this export, but the spec's
     * SNOMED path names Observation, Procedure and Condition, so mapping it would assert
     * something Horizon has not specified.
     */
    private static void applyEncounter(JsonNode theRecord, EcdsRow theRow) {
        FhirPaths.array(theRecord, "type")
                .map(type -> first(type, CodeSystems.CPT))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst()
                .ifPresent(code -> theRow.set(EcdsColumn.CPTPx, code));
    }

    /**
     * RxNorm may sit inline or on a Medication the request points at.
     *
     * <p>The mapping says to "resolve {@code medicationReference} to the contained <em>or
     * referenced</em> Medication resource when {@code medicationCodeableConcept} is absent".
     * The CLI only ever read a contained one — there was no lookup to hand it — so a
     * MedicationRequest written as {@code medicationReference: Medication/123} lost its RxNorm
     * code. Inside the CDR the Medication is one read away, and Niks's export pulls Medication
     * for exactly this reason. Inline still wins, so this can only fill a column that was blank.
     */
    private static void applyMedication(JsonNode theRecord, EcdsRow theRow, ResourceResolver theResolver) {
        JsonNode inline = theRecord.get("medicationCodeableConcept");
        if (first(inline, CodeSystems.RXNORM).isPresent()) {
            theRow.set(EcdsColumn.RxNorm, first(inline, CodeSystems.RXNORM).orElse(""));
            return;
        }
        FhirPaths.reference(theRecord.get("medicationReference"))
                .flatMap(reference -> medication(theRecord, reference, theResolver))
                .flatMap(medication -> first(medication.get("code"), CodeSystems.RXNORM))
                .ifPresent(v -> theRow.set(EcdsColumn.RxNorm, v));
    }

    private static Optional<JsonNode> medication(JsonNode theRecord, String theReference,
                                                 ResourceResolver theResolver) {
        if (theResolver != null) {
            return theResolver.resolve(theReference, theRecord);
        }
        return theReference.startsWith("#")
                ? containedById(theRecord, theReference.substring(1))
                : Optional.empty();
    }

    /**
     * Only completed immunisations are emitted. A refusal or contraindication
     * ({@code not-done}) or an {@code entered-in-error} record is suppressed entirely rather
     * than emitted with a reason code.
     */
    private static void applyImmunization(JsonNode theRecord, EcdsRow theRow) {
        if (!"completed".equals(FhirPaths.primitive(theRecord, "status").orElse(""))) {
            return;
        }
        first(theRecord.get("vaccineCode"), CodeSystems.CVX)
                .ifPresent(v -> theRow.set(EcdsColumn.CVX, v));
    }

    /** Principal first, then secondaries in order, never repeating the principal. */
    private static void applyDiagnoses(List<String> theIcdCodes, EcdsRow theRow) {
        List<String> distinct = new ArrayList<>();
        for (String code : theIcdCodes) {
            String stripped = CodeSystems.stripDecimal(code);
            if (!stripped.isEmpty() && !distinct.contains(stripped)) {
                distinct.add(stripped);
            }
        }
        for (int i = 0; i < Math.min(distinct.size(), DIAGNOSIS_COLUMNS.size()); i++) {
            theRow.set(DIAGNOSIS_COLUMNS.get(i), distinct.get(i));
        }
    }

    private static java.util.Optional<String> first(JsonNode theCodeableConcept, String theSystem) {
        return FhirPaths.firstCode(theCodeableConcept, theSystem);
    }

    private static java.util.Optional<JsonNode> containedById(JsonNode theParent, String theId) {
        return FhirPaths.array(theParent, "contained")
                .filter(contained -> FhirPaths.primitive(contained, "id").filter(theId::equals).isPresent())
                .findFirst();
    }

    /** The numeric value only; the ECDS column is unitless. */
    private static java.util.Optional<String> numericValue(JsonNode theQuantity) {
        if (theQuantity == null) {
            return java.util.Optional.empty();
        }
        JsonNode value = theQuantity.get("value");
        if (value == null || !value.isNumber()) {
            return java.util.Optional.empty();
        }
        // stripTrailingZeros keeps an integral value from rendering as "120.0".
        return java.util.Optional.of(new BigDecimal(value.asText()).stripTrailingZeros().toPlainString());
    }
}
