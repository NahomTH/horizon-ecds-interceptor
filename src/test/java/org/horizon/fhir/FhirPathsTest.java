package org.horizon.fhir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixtures are the real shapes found in the sample export, not invented ones —
 * the resource ids in the comments point at the record each was taken from.
 */
class FhirPathsTest {

    private static final String LOINC = "http://loinc.org";
    private static final String SNOMED = "http://snomed.info/sct";
    private static final String CPT = "http://www.ama-assn.org/go/cpt";

    private final ObjectMapper myMapper = new ObjectMapper();

    // --- isAbsent ---------------------------------------------------------

    @Test
    void absentWhenTheNodeIsMissing() {
        JsonNode resource = parse("{}");

        assertThat(FhirPaths.isAbsent(resource.get("code"))).isTrue();
    }

    @Test
    void absentWhenTheNodeIsAnExplicitJsonNull() {
        JsonNode resource = parse("{\"code\": null}");

        assertThat(FhirPaths.isAbsent(resource.get("code"))).isTrue();
    }

    @Test
    void absentWhenTheCodeableConceptCarriesOnlyADataAbsentReason() {
        // Condition/2577
        JsonNode code = parse("""
                {"extension":[{"url":"http://hl7.org/fhir/StructureDefinition/data-absent-reason",
                               "valueCode":"unknown"}]}
                """);

        assertThat(FhirPaths.isAbsent(code)).isTrue();
    }

    @Test
    void presentWhenTheNodeCarriesRealContent() {
        JsonNode code = parse("{\"coding\":[{\"system\":\"http://loinc.org\",\"code\":\"4548-4\"}]}");

        assertThat(FhirPaths.isAbsent(code)).isFalse();
    }

    @Test
    void dataAbsentReasonElementIsNotConfusedWithTheDataAbsentReasonExtension() {
        // Observation.dataAbsentReason is a real element meaning "no value was obtained".
        // It must not make the Observation itself look absent.
        JsonNode observation = parse("""
                {"resourceType":"Observation",
                 "dataAbsentReason":{"coding":[{"code":"unknown"}]}}
                """);

        assertThat(FhirPaths.isAbsent(observation)).isFalse();
    }

    // --- primitive --------------------------------------------------------

    @Test
    void primitiveReturnsTheValueWhenPresent() {
        JsonNode resource = parse("{\"birthDate\":\"2014-02-01\"}");

        assertThat(FhirPaths.primitive(resource, "birthDate")).contains("2014-02-01");
    }

    @Test
    void primitiveIsEmptyWhenOnlyTheUnderscorePrefixedElementIsPresent() {
        // DiagnosticReport: every one in the export carries _issued, never issued.
        JsonNode resource = parse("""
                {"_issued":{"extension":[{"url":"http://hl7.org/fhir/StructureDefinition/data-absent-reason",
                                          "valueCode":"unknown"}]}}
                """);

        assertThat(FhirPaths.primitive(resource, "issued")).isEmpty();
    }

    @Test
    void primitiveDoesNotReturnTheStringNullForAnExplicitJsonNull() {
        JsonNode resource = parse("{\"birthDate\": null}");

        assertThat(FhirPaths.primitive(resource, "birthDate")).isEmpty();
    }

    @Test
    void primitiveIsEmptyForABlankValue() {
        JsonNode resource = parse("{\"birthDate\":\"   \"}");

        assertThat(FhirPaths.primitive(resource, "birthDate")).isEmpty();
    }

    // --- codes ------------------------------------------------------------

    @Test
    void codesReturnsTheMatchingCodeForTheSystem() {
        JsonNode code = parse("{\"coding\":[{\"system\":\"http://loinc.org\",\"code\":\"4548-4\"}]}");

        assertThat(FhirPaths.codes(code, LOINC)).containsExactly("4548-4");
    }

    @Test
    void codesReturnsEveryMatchingCodingInOrder() {
        // Procedure/2613 carries three CPT codes; this is what drives row explosion.
        JsonNode code = parse("""
                {"coding":[{"system":"http://www.ama-assn.org/go/cpt","code":"95251"},
                           {"system":"http://www.ama-assn.org/go/cpt","code":"95249"},
                           {"system":"http://www.ama-assn.org/go/cpt","code":"95250"}]}
                """);

        assertThat(FhirPaths.codes(code, CPT)).containsExactly("95251", "95249", "95250");
    }

    @Test
    void codingWithNoSystemNeverMatchesASystemFilter() {
        // Observation/2678: one LOINC coding followed by six systemless ones.
        JsonNode code = parse("""
                {"coding":[{"system":"http://loinc.org","code":"72234-8"},
                           {"code":"4719001"},
                           {"code":"113024001"}]}
                """);

        assertThat(FhirPaths.codes(code, LOINC)).containsExactly("72234-8");
        assertThat(FhirPaths.codes(code, SNOMED)).isEmpty();
    }

    @Test
    void codesSkipsACodingThatCarriesADataAbsentReasonInsteadOfACode() {
        // Observation/2635: the coding has a system but no code, only a data-absent extension.
        JsonNode code = parse("""
                {"coding":[{"extension":[{"url":"http://hl7.org/fhir/StructureDefinition/data-absent-reason",
                                          "valueCode":"unknown"}],
                            "system":"http://loinc.org"}]}
                """);

        assertThat(FhirPaths.codes(code, LOINC)).isEmpty();
    }

    @Test
    void absentCodingIsSkippedButLaterPopulatedCodingsAreStillReturned() {
        JsonNode code = parse("""
                {"coding":[{"extension":[{"url":"http://hl7.org/fhir/StructureDefinition/data-absent-reason",
                                          "valueCode":"unknown"}],
                            "system":"http://loinc.org"},
                           {"system":"http://loinc.org","code":"4548-4"}]}
                """);

        assertThat(FhirPaths.codes(code, LOINC)).containsExactly("4548-4");
    }

    @Test
    void codesIsEmptyWhenTheWholeCodeableConceptIsDataAbsent() {
        JsonNode code = parse("""
                {"extension":[{"url":"http://hl7.org/fhir/StructureDefinition/data-absent-reason",
                               "valueCode":"unknown"}]}
                """);

        assertThat(FhirPaths.codes(code, LOINC)).isEmpty();
    }

    @Test
    void aRealCodingIsStillReturnedWhenTheConceptAlsoCarriesADataAbsentReason() {
        // Procedure/2707 in the export is marked data-absent yet carries a usable HCPCS
        // coding. The populated coding contradicts the marker, so the code must not be lost.
        JsonNode code = parse("""
                {"extension":[{"url":"http://hl7.org/fhir/StructureDefinition/data-absent-reason",
                               "valueCode":"unknown"}],
                 "coding":[{"system":"urn:oid:2.16.840.1.113883.6.285","code":"H0031"}]}
                """);

        assertThat(FhirPaths.codes(code, "urn:oid:2.16.840.1.113883.6.285")).containsExactly("H0031");
    }

    @Test
    void aConceptMarkedAbsentWithNoCodingsYieldsNothing() {
        JsonNode code = parse("""
                {"extension":[{"url":"http://hl7.org/fhir/StructureDefinition/data-absent-reason",
                               "valueCode":"unknown"}]}
                """);

        assertThat(FhirPaths.codes(code, LOINC)).isEmpty();
    }

    @Test
    void codesIsEmptyForAMissingNode() {
        assertThat(FhirPaths.codes(parse("{}").get("code"), LOINC)).isEmpty();
    }

    @Test
    void firstCodeReturnsTheLeadingMatch() {
        JsonNode code = parse("""
                {"coding":[{"system":"http://www.ama-assn.org/go/cpt","code":"95251"},
                           {"system":"http://www.ama-assn.org/go/cpt","code":"95249"}]}
                """);

        assertThat(FhirPaths.firstCode(code, CPT)).contains("95251");
    }

    @Test
    void firstCodeIsEmptyWhenNoCodingMatches() {
        JsonNode code = parse("{\"coding\":[{\"system\":\"http://loinc.org\",\"code\":\"4548-4\"}]}");

        assertThat(FhirPaths.firstCode(code, SNOMED)).isEmpty();
    }

    // --- extensionValue ---------------------------------------------------

    @Test
    void extensionValueReadsAValueCode() {
        // Patient/2518 us-core-birthsex
        JsonNode patient = parse("""
                {"extension":[{"url":"http://hl7.org/fhir/us/core/StructureDefinition/us-core-birthsex",
                               "valueCode":"UNK"}]}
                """);

        assertThat(FhirPaths.extensionValue(patient,
                "http://hl7.org/fhir/us/core/StructureDefinition/us-core-birthsex")).contains("UNK");
    }

    @Test
    void extensionValueReadsAValueString() {
        JsonNode patient = parse("""
                {"extension":[{"url":"http://example.org/note","valueString":"free text"}]}
                """);

        assertThat(FhirPaths.extensionValue(patient, "http://example.org/note")).contains("free text");
    }

    @Test
    void extensionValueReadsTheCodeFromAValueCoding() {
        // us-core-genderIdentity in the export carries its value this way.
        JsonNode patient = parse("""
                {"extension":[{"url":"http://hl7.org/fhir/us/core/StructureDefinition/us-core-genderIdentity",
                               "valueCodeableConcept":{"coding":[{"code":"446141000124107"}]}},
                              {"url":"http://example.org/coded",
                               "valueCoding":{"system":"http://snomed.info/sct","code":"248152002"}}]}
                """);

        assertThat(FhirPaths.extensionValue(patient, "http://example.org/coded")).contains("248152002");
    }

    @Test
    void extensionValueIsEmptyWhenTheMatchingExtensionCarriesNoReadableValue() {
        JsonNode patient = parse("""
                {"extension":[{"url":"http://example.org/nested",
                               "extension":[{"url":"ombCategory","valueCoding":{"code":"2054-5"}}]}]}
                """);

        assertThat(FhirPaths.extensionValue(patient, "http://example.org/nested")).isEmpty();
    }

    @Test
    void extensionValueIsEmptyWhenTheUrlIsNotPresent() {
        JsonNode patient = parse("{\"extension\":[{\"url\":\"http://example.org/other\",\"valueCode\":\"X\"}]}");

        assertThat(FhirPaths.extensionValue(patient, "http://example.org/wanted")).isEmpty();
    }

    @Test
    void extensionValueIsEmptyWhenThereAreNoExtensions() {
        assertThat(FhirPaths.extensionValue(parse("{}"), "http://example.org/wanted")).isEmpty();
    }

    // --- reference / array ------------------------------------------------

    @Test
    void referenceReturnsTheTypeAndId() {
        JsonNode subject = parse("{\"reference\":\"Patient/2518\"}");

        assertThat(FhirPaths.reference(subject)).contains("Patient/2518");
    }

    @Test
    void referenceIsEmptyForAMissingNode() {
        assertThat(FhirPaths.reference(parse("{}").get("subject"))).isEmpty();
    }

    @Test
    void arrayStreamsEveryElement() {
        JsonNode resource = parse("{\"component\":[{\"a\":1},{\"a\":2}]}");

        assertThat(FhirPaths.array(resource, "component")).hasSize(2);
    }

    @Test
    void arrayIsEmptyForAMissingOrNonArrayField() {
        JsonNode resource = parse("{\"component\":\"notAnArray\"}");

        assertThat(FhirPaths.array(resource, "component")).isEmpty();
        assertThat(FhirPaths.array(resource, "absent")).isEmpty();
    }

    private JsonNode parse(String theJson) {
        try {
            return myMapper.readTree(theJson);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** Guards the signature the extractors depend on. */
    @SuppressWarnings("unused")
    private static List<String> signatureCheck(JsonNode theNode) {
        return FhirPaths.codes(theNode, LOINC);
    }
}
