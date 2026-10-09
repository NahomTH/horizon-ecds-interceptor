package org.horizon.ecds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.horizon.fhir.ReferenceIndex;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Fixtures follow the real shapes in the sample export. */
class CodeColumnsTest {

    private final ObjectMapper myMapper = new ObjectMapper();

    // --- LOINC / SNOMED ----------------------------------------------------

    @Test
    void observationLoincComesFromItsCode() {
        EcdsRow row = apply("""
                {"resourceType":"Observation","id":"1",
                 "code":{"coding":[{"system":"http://loinc.org","code":"4548-4"}]}}
                """);

        assertThat(row.get(EcdsColumn.LOINC)).isEqualTo("4548-4");
    }

    @Test
    void loincRetainsItsHyphen() {
        EcdsRow row = apply("""
                {"resourceType":"Observation","id":"1",
                 "code":{"coding":[{"system":"http://loinc.org","code":"72234-8"}]}}
                """);

        assertThat(row.get(EcdsColumn.LOINC)).isEqualTo("72234-8");
    }

    @Test
    void diagnosticReportLoincComesFromItsCode() {
        EcdsRow row = apply("""
                {"resourceType":"DiagnosticReport","id":"1",
                 "code":{"coding":[{"system":"http://loinc.org","code":"58410-2"}]}}
                """);

        assertThat(row.get(EcdsColumn.LOINC)).isEqualTo("58410-2");
    }

    @Test
    void conditionSnomedComesFromItsCode() {
        EcdsRow row = apply("""
                {"resourceType":"Condition","id":"1",
                 "code":{"coding":[{"system":"http://snomed.info/sct","code":"44054006"}]}}
                """);

        assertThat(row.get(EcdsColumn.SNOMED)).isEqualTo("44054006");
    }

    @Test
    void observationSnomedComesFromItsValueNotItsCode() {
        // The spec maps Observation.valueCodeableConcept to SNOMED — the coded answer,
        // not the question.
        EcdsRow row = apply("""
                {"resourceType":"Observation","id":"2771",
                 "code":{"coding":[{"system":"http://loinc.org","code":"72166-2"}]},
                 "valueCodeableConcept":{"coding":[{"system":"http://snomed.info/sct","code":"8517006"}]}}
                """);

        assertThat(row.get(EcdsColumn.LOINC)).isEqualTo("72166-2");
        assertThat(row.get(EcdsColumn.SNOMED)).isEqualTo("8517006");
    }

    @Test
    void procedureSnomedComesFromItsCode() {
        EcdsRow row = apply("""
                {"resourceType":"Procedure","id":"1",
                 "code":{"coding":[{"system":"http://snomed.info/sct","code":"73761001"}]}}
                """);

        assertThat(row.get(EcdsColumn.SNOMED)).isEqualTo("73761001");
    }

    // --- no terminology uplift ---------------------------------------------

    @Test
    void aSnomedOnlyConditionLeavesTheIcdColumnsBlank() {
        // Horizon directed no SNOMED-to-ICD-10 uplift in either direction.
        EcdsRow row = apply("""
                {"resourceType":"Condition","id":"1",
                 "code":{"coding":[{"system":"http://snomed.info/sct","code":"44054006"}]}}
                """);

        assertThat(row.get(EcdsColumn.SNOMED)).isEqualTo("44054006");
        assertThat(row.get(EcdsColumn.ICDDx10_00)).isEmpty();
    }

    @Test
    void anIcdOnlyConditionLeavesTheSnomedColumnBlank() {
        EcdsRow row = apply("""
                {"resourceType":"Condition","id":"1",
                 "code":{"coding":[{"system":"http://hl7.org/fhir/sid/icd-10-cm","code":"E11.22"}]}}
                """);

        assertThat(row.get(EcdsColumn.ICDDx10_00)).isEqualTo("E1122");
        assertThat(row.get(EcdsColumn.SNOMED)).isEmpty();
    }

    @Test
    void aConditionCarryingBothEmitsBothIndependently() {
        EcdsRow row = apply("""
                {"resourceType":"Condition","id":"1",
                 "code":{"coding":[{"system":"http://snomed.info/sct","code":"44054006"},
                                   {"system":"http://hl7.org/fhir/sid/icd-10-cm","code":"E11.22"}]}}
                """);

        assertThat(row.get(EcdsColumn.SNOMED)).isEqualTo("44054006");
        assertThat(row.get(EcdsColumn.ICDDx10_00)).isEqualTo("E1122");
    }

    // --- ICD-10 diagnosis columns ------------------------------------------

    @Test
    void theDecimalIsStrippedFromIcdDiagnoses() {
        EcdsRow row = apply("""
                {"resourceType":"Condition","id":"1",
                 "code":{"coding":[{"system":"http://hl7.org/fhir/sid/icd-10-cm","code":"Z00.00"}]}}
                """);

        assertThat(row.get(EcdsColumn.ICDDx10_00)).isEqualTo("Z0000");
    }

    @Test
    void severalIcdCodingsFillTheSecondaryDiagnosisColumns() {
        EcdsRow row = apply("""
                {"resourceType":"Condition","id":"1",
                 "code":{"coding":[{"system":"http://hl7.org/fhir/sid/icd-10-cm","code":"E11.22"},
                                   {"system":"http://hl7.org/fhir/sid/icd-10-cm","code":"N18.30"},
                                   {"system":"http://hl7.org/fhir/sid/icd-10-cm","code":"Z79.4"}]}}
                """);

        assertThat(row.get(EcdsColumn.ICDDx10_00)).isEqualTo("E1122");
        assertThat(row.get(EcdsColumn.ICDDx10_01)).isEqualTo("N1830");
        assertThat(row.get(EcdsColumn.ICDDx10_02)).isEqualTo("Z794");
    }

    @Test
    void theSecondaryDiagnosisColumnsDoNotRepeatThePrincipal() {
        // Horizon rule: de-duplicate against ICDDx10_00 before writing.
        EcdsRow row = apply("""
                {"resourceType":"Condition","id":"1",
                 "code":{"coding":[{"system":"http://hl7.org/fhir/sid/icd-10-cm","code":"E11.22"},
                                   {"system":"http://hl7.org/fhir/sid/icd-10-cm","code":"E11.22"},
                                   {"system":"http://hl7.org/fhir/sid/icd-10-cm","code":"Z79.4"}]}}
                """);

        assertThat(row.get(EcdsColumn.ICDDx10_00)).isEqualTo("E1122");
        assertThat(row.get(EcdsColumn.ICDDx10_01)).isEqualTo("Z794");
        assertThat(row.get(EcdsColumn.ICDDx10_02)).isEmpty();
    }

    @Test
    void noMoreThanTenDiagnosisColumnsAreFilled() {
        StringBuilder codings = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            codings.append(i > 0 ? "," : "")
                    .append("{\"system\":\"http://hl7.org/fhir/sid/icd-10-cm\",\"code\":\"A0%d.1\"}".formatted(i));
        }
        EcdsRow row = apply("{\"resourceType\":\"Condition\",\"id\":\"1\",\"code\":{\"coding\":["
                + codings + "]}}");

        assertThat(row.get(EcdsColumn.ICDDx10_09)).isNotEmpty();
    }

    // --- procedure codes ----------------------------------------------------

    @Test
    void procedureCptIsEmitted() {
        EcdsRow row = apply("""
                {"resourceType":"Procedure","id":"1",
                 "code":{"coding":[{"system":"http://www.ama-assn.org/go/cpt","code":"95251"}]}}
                """);

        assertThat(row.get(EcdsColumn.CPTPx)).isEqualTo("95251");
    }

    // --- Encounter ----------------------------------------------------------

    @Test
    void encounterCptComesFromItsTypeElement() {
        // Encounter/3457 in the export, which produces a row with no code today.
        EcdsRow row = apply("""
                {"resourceType":"Encounter","id":"3457","type":[{"coding":[
                  {"system":"http://www.ama-assn.org/go/cpt","code":"99213",
                   "display":"Office/outpatient established low mdm 20 min"}]}]}
                """);

        assertThat(row.get(EcdsColumn.CPTPx)).isEqualTo("99213");
    }

    @Test
    void encounterTypeIsReadAsAnArrayOfConcepts() {
        // type is a list of CodeableConcepts, unlike the single code element every other
        // resource exposes, so reading only the first entry would miss this.
        EcdsRow row = apply("""
                {"resourceType":"Encounter","id":"1","type":[
                  {"coding":[{"system":"http://snomed.info/sct","code":"308471005"}]},
                  {"coding":[{"system":"http://www.ama-assn.org/go/cpt","code":"99213"}]}]}
                """);

        assertThat(row.get(EcdsColumn.CPTPx)).isEqualTo("99213");
    }

    @Test
    void anEncounterWithNoCptLeavesTheColumnBlank() {
        EcdsRow row = apply("""
                {"resourceType":"Encounter","id":"1","type":[{"coding":[
                  {"system":"http://terminology.hl7.org/CodeSystem/v3-ActCode","code":"AMB"}]}]}
                """);

        assertThat(row.get(EcdsColumn.CPTPx)).isEmpty();
    }

    @Test
    void anEncounterWithADataAbsentTypeLeavesTheColumnBlank() {
        // 13 of the 20 Encounters in the export are this shape.
        EcdsRow row = apply("""
                {"resourceType":"Encounter","id":"1","type":[{"coding":[
                  {"extension":[{"url":"http://hl7.org/fhir/StructureDefinition/data-absent-reason",
                                 "valueCode":"unknown"}],
                   "system":"http://www.ama-assn.org/go/cpt"}]}]}
                """);

        assertThat(row.get(EcdsColumn.CPTPx)).isEmpty();
    }

    @Test
    void encounterSnomedIsNotMapped() {
        // The spec's SNOMED path names Observation, Procedure and Condition — not Encounter.
        // Deliberately out of scope rather than overlooked; raised with Horizon instead.
        EcdsRow row = apply("""
                {"resourceType":"Encounter","id":"2693","type":[{"coding":[
                  {"system":"http://snomed.info/sct","code":"308471005",
                   "display":"Referral to cardiologist"}]}]}
                """);

        assertThat(row.get(EcdsColumn.SNOMED)).isEmpty();
        assertThat(row.get(EcdsColumn.CPTPx)).isEmpty();
    }

    @Test
    void procedureHcpcsIsEmitted() {
        EcdsRow row = apply("""
                {"resourceType":"Procedure","id":"1",
                 "code":{"coding":[{"system":"urn:oid:2.16.840.1.113883.6.285","code":"G0105"}]}}
                """);

        assertThat(row.get(EcdsColumn.HCPCSPx)).isEqualTo("G0105");
    }

    @Test
    void procedureIcdTenPcsHasItsDecimalStripped() {
        EcdsRow row = apply("""
                {"resourceType":"Procedure","id":"1",
                 "code":{"coding":[{"system":"http://www.cms.gov/Medicare/Coding/ICD10","code":"0FT.44ZZ"}]}}
                """);

        assertThat(row.get(EcdsColumn.ICDPx10)).isEqualTo("0FT44ZZ");
    }

    @Test
    void anIcdNineDiagnosisGoesToTheIcdDxColumn() {
        EcdsRow row = apply("""
                {"resourceType":"Condition","id":"1",
                 "code":{"coding":[{"system":"urn:oid:2.16.840.1.113883.6.103","code":"250.00"}]}}
                """);

        assertThat(row.get(EcdsColumn.ICDDX)).isEqualTo("25000");
    }

    // --- medications and immunisations -------------------------------------

    @Test
    void medicationRequestRxNormIsEmitted() {
        EcdsRow row = apply("""
                {"resourceType":"MedicationRequest","id":"1",
                 "medicationCodeableConcept":{"coding":[
                   {"system":"http://www.nlm.nih.gov/research/umls/rxnorm","code":"860975"}]}}
                """);

        assertThat(row.get(EcdsColumn.RxNorm)).isEqualTo("860975");
    }

    @Test
    void medicationReferenceResolvesToAContainedMedication() {
        EcdsRow row = apply("""
                {"resourceType":"MedicationRequest","id":"1",
                 "medicationReference":{"reference":"#med-1"},
                 "contained":[{"resourceType":"Medication","id":"med-1",
                   "code":{"coding":[{"system":"http://www.nlm.nih.gov/research/umls/rxnorm","code":"860975"}]}}]}
                """);

        assertThat(row.get(EcdsColumn.RxNorm)).isEqualTo("860975");
    }

    @Test
    void medicationReferenceResolvesToAMedicationOfItsOwn() {
        // The mapping says "contained OR REFERENCED". Inside the CDR the Medication is a read
        // away, and the reference export pulls Medication for exactly this.
        ReferenceIndex index = new ReferenceIndex();
        index.add(parse("""
                {"resourceType":"Medication","id":"med-9",
                 "code":{"coding":[{"system":"http://www.nlm.nih.gov/research/umls/rxnorm","code":"860975"}]}}
                """));

        EcdsRow row = new EcdsRow();
        CodeColumns.applyTo(parse("""
                {"resourceType":"MedicationRequest","id":"1",
                 "medicationReference":{"reference":"Medication/med-9"}}
                """), row, index);

        assertThat(row.get(EcdsColumn.RxNorm)).isEqualTo("860975");
    }

    @Test
    void anInlineRxNormStillWinsOverAReferencedMedication() {
        ReferenceIndex index = new ReferenceIndex();
        index.add(parse("""
                {"resourceType":"Medication","id":"med-9",
                 "code":{"coding":[{"system":"http://www.nlm.nih.gov/research/umls/rxnorm","code":"111111"}]}}
                """));

        EcdsRow row = new EcdsRow();
        CodeColumns.applyTo(parse("""
                {"resourceType":"MedicationRequest","id":"1",
                 "medicationReference":{"reference":"Medication/med-9"},
                 "medicationCodeableConcept":{"coding":[
                   {"system":"http://www.nlm.nih.gov/research/umls/rxnorm","code":"860975"}]}}
                """), row, index);

        assertThat(row.get(EcdsColumn.RxNorm)).isEqualTo("860975");
    }

    @Test
    void anExternalMedicationIsLeftAloneWithNoResolver() {
        EcdsRow row = apply("""
                {"resourceType":"MedicationRequest","id":"1",
                 "medicationReference":{"reference":"Medication/med-9"}}
                """);

        assertThat(row.get(EcdsColumn.RxNorm)).isEmpty();
    }

    @Test
    void medicationDispenseReadsItsRxNormTheSameWay() {
        EcdsRow row = apply("""
                {"resourceType":"MedicationDispense","id":"1",
                 "medicationCodeableConcept":{"coding":[
                   {"system":"http://www.nlm.nih.gov/research/umls/rxnorm","code":"860975"}]}}
                """);

        assertThat(row.get(EcdsColumn.RxNorm)).isEqualTo("860975");
    }

    @Test
    void completedImmunizationEmitsItsCvxCode() {
        EcdsRow row = apply("""
                {"resourceType":"Immunization","id":"1","status":"completed",
                 "vaccineCode":{"coding":[{"system":"http://hl7.org/fhir/sid/cvx","code":"140"}]}}
                """);

        assertThat(row.get(EcdsColumn.CVX)).isEqualTo("140");
    }

    // --- results ------------------------------------------------------------

    @Test
    void aNumericObservationValueGoesToResult() {
        EcdsRow row = apply("""
                {"resourceType":"Observation","id":"1",
                 "code":{"coding":[{"system":"http://loinc.org","code":"4548-4"}]},
                 "valueQuantity":{"value":5.96,"unit":"%"}}
                """);

        // The unit is dropped; the column is unitless.
        assertThat(row.get(EcdsColumn.Result)).isEqualTo("5.96");
    }

    @Test
    void anIntegerResultIsNotRenderedWithATrailingDecimal() {
        EcdsRow row = apply("""
                {"resourceType":"Observation","id":"1","valueQuantity":{"value":120}}
                """);

        assertThat(row.get(EcdsColumn.Result)).isEqualTo("120");
    }

    @Test
    void aCodedObservationAnswerGoesToLoincAnswerNotResult() {
        EcdsRow row = apply("""
                {"resourceType":"Observation","id":"2771",
                 "valueCodeableConcept":{"coding":[{"system":"http://snomed.info/sct","code":"8517006"}]}}
                """);

        assertThat(row.get(EcdsColumn.LOINCAnswer)).isEqualTo("8517006");
        assertThat(row.get(EcdsColumn.Result)).isEmpty();
    }

    @Test
    void aNullFlavouredAnswerIsNotEmitted() {
        // Observation/2614 carries valueCodeableConcept UNK under the NullFlavor system.
        EcdsRow row = apply("""
                {"resourceType":"Observation","id":"2614",
                 "valueCodeableConcept":{"coding":[
                   {"system":"http://terminology.hl7.org/CodeSystem/v3-NullFlavor","code":"UNK"}]}}
                """);

        assertThat(row.get(EcdsColumn.LOINCAnswer)).isEmpty();
        assertThat(row.get(EcdsColumn.SNOMED)).isEmpty();
    }

    @Test
    void aDataAbsentCodeLeavesTheColumnBlank() {
        EcdsRow row = apply("""
                {"resourceType":"Observation","id":"2635",
                 "code":{"coding":[{"extension":[{"url":"http://hl7.org/fhir/StructureDefinition/data-absent-reason",
                                                  "valueCode":"unknown"}],
                                    "system":"http://loinc.org"}]}}
                """);

        assertThat(row.get(EcdsColumn.LOINC)).isEmpty();
    }

    private EcdsRow apply(String theJson) {
        EcdsRow row = new EcdsRow();
        CodeColumns.applyTo(parse(theJson), row);
        return row;
    }

    private JsonNode parse(String theJson) {
        try {
            return myMapper.readTree(theJson);
        } catch (Exception e) {
            throw new IllegalArgumentException(theJson, e);
        }
    }
}
