package org.horizon.ecds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One source record can yield several ECDS rows. The three multiplying rules are
 * mutually exclusive in practice, so each extractor owns its own.
 */
class RowExplosionTest {

    private final ObjectMapper myMapper = new ObjectMapper();

    // --- multi-CPT procedures ----------------------------------------------

    @Test
    void aProcedureWithThreeCptCodesEmitsThreeRows() {
        // Procedure/2713 in the sample export.
        List<EcdsRow> rows = explode("""
                {"resourceType":"Procedure","id":"2713","performedDateTime":"2026-05-05",
                 "code":{"coding":[{"system":"http://www.ama-assn.org/go/cpt","code":"95251"},
                                   {"system":"http://www.ama-assn.org/go/cpt","code":"95249"},
                                   {"system":"http://www.ama-assn.org/go/cpt","code":"95250"}]}}
                """);

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(row -> row.get(EcdsColumn.CPTPx))
                .containsExactly("95251", "95249", "95250");
    }

    @Test
    void explodedProcedureRowsShareEveryColumnExceptTheCptCode() {
        List<EcdsRow> rows = explode("""
                {"resourceType":"Procedure","id":"2713","performedDateTime":"2026-05-05",
                 "code":{"coding":[{"system":"http://www.ama-assn.org/go/cpt","code":"95251"},
                                   {"system":"http://www.ama-assn.org/go/cpt","code":"95249"},
                                   {"system":"http://snomed.info/sct","code":"73761001"}]}}
                """);

        assertThat(rows).hasSize(2);
        for (EcdsRow row : rows) {
            assertThat(row.get(EcdsColumn.DOS)).isEqualTo("2026-05-05");
            assertThat(row.get(EcdsColumn.SNOMED)).isEqualTo("73761001");
            assertThat(row.get(EcdsColumn.Ecdsaltid_00)).isEqualTo("Procedure/2713");
        }
    }

    @Test
    void aProcedureWithOneCptEmitsOneRow() {
        List<EcdsRow> rows = explode("""
                {"resourceType":"Procedure","id":"1",
                 "code":{"coding":[{"system":"http://www.ama-assn.org/go/cpt","code":"45330"}]}}
                """);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(EcdsColumn.CPTPx)).isEqualTo("45330");
    }

    @Test
    void aProcedureWithNoCptStillEmitsOneRow() {
        List<EcdsRow> rows = explode("""
                {"resourceType":"Procedure","id":"1",
                 "code":{"coding":[{"system":"http://snomed.info/sct","code":"73761001"}]}}
                """);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(EcdsColumn.CPTPx)).isEmpty();
        assertThat(rows.get(0).get(EcdsColumn.SNOMED)).isEqualTo("73761001");
    }

    // --- blood pressure components -----------------------------------------

    @Test
    void aBloodPressureObservationEmitsOneRowPerComponent() {
        // Observation/2789 in the sample export.
        List<EcdsRow> rows = explode("""
                {"resourceType":"Observation","id":"2789","effectiveDateTime":"2026-06-05",
                 "code":{"coding":[{"system":"http://loinc.org","code":"85354-9"}]},
                 "component":[
                   {"code":{"coding":[{"system":"http://loinc.org","code":"8480-6"}]},
                    "valueQuantity":{"value":120}},
                   {"code":{"coding":[{"system":"http://loinc.org","code":"8462-4"}]},
                    "valueQuantity":{"value":80}}]}
                """);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> row.get(EcdsColumn.LOINC))
                .containsExactly("8480-6", "8462-4");
        assertThat(rows).extracting(row -> row.get(EcdsColumn.Result))
                .containsExactly("120", "80");
    }

    @Test
    void componentRowsCarryTheComponentLoincNotThePanelLoinc() {
        List<EcdsRow> rows = explode("""
                {"resourceType":"Observation","id":"2789",
                 "code":{"coding":[{"system":"http://loinc.org","code":"85354-9"}]},
                 "component":[
                   {"code":{"coding":[{"system":"http://loinc.org","code":"8480-6"}]},
                    "valueQuantity":{"value":120}}]}
                """);

        assertThat(rows.get(0).get(EcdsColumn.LOINC)).isEqualTo("8480-6");
    }

    @Test
    void anObservationWithNoComponentsEmitsOneRow() {
        List<EcdsRow> rows = explode("""
                {"resourceType":"Observation","id":"1",
                 "code":{"coding":[{"system":"http://loinc.org","code":"4548-4"}]},
                 "valueQuantity":{"value":5.96}}
                """);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(EcdsColumn.Result)).isEqualTo("5.96");
    }

    // --- hasMember panels ---------------------------------------------------

    @Test
    void aPanelObservationEmitsOneRowPerResolvableChild() {
        // Observation/2778: the child is a contained resource carrying valueInteger.
        List<EcdsRow> rows = explode("""
                {"resourceType":"Observation","id":"2778","effectiveDateTime":"2026-06-05",
                 "code":{"coding":[{"system":"http://loinc.org","code":"72234-8"}]},
                 "hasMember":[{"reference":"#child-1"}],
                 "contained":[{"resourceType":"Observation","id":"child-1",
                   "code":{"coding":[{"system":"http://loinc.org","code":"72233-0"}]},
                   "valueInteger":1}]}
                """);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(EcdsColumn.LOINC)).isEqualTo("72233-0");
        assertThat(rows.get(0).get(EcdsColumn.Result)).isEqualTo("1");
    }

    @Test
    void aPanelChildWithACodedAnswerFillsLoincAnswer() {
        List<EcdsRow> rows = explode("""
                {"resourceType":"Observation","id":"1",
                 "hasMember":[{"reference":"#child-1"}],
                 "contained":[{"resourceType":"Observation","id":"child-1",
                   "code":{"coding":[{"system":"http://loinc.org","code":"72233-0"}]},
                   "valueCodeableConcept":{"coding":[{"system":"http://snomed.info/sct","code":"8517006"}]}}]}
                """);

        assertThat(rows.get(0).get(EcdsColumn.LOINCAnswer)).isEqualTo("8517006");
    }

    @Test
    void aPanelWhoseChildCannotBeResolvedStillEmitsTheParentRow() {
        List<EcdsRow> rows = explode("""
                {"resourceType":"Observation","id":"1",
                 "code":{"coding":[{"system":"http://loinc.org","code":"72234-8"}]},
                 "hasMember":[{"reference":"#missing"}]}
                """);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(EcdsColumn.LOINC)).isEqualTo("72234-8");
    }

    // --- suppression --------------------------------------------------------

    @Test
    void aNotDoneImmunizationEmitsNoRows() {
        assertThat(explode("""
                {"resourceType":"Immunization","id":"1","status":"not-done",
                 "vaccineCode":{"coding":[{"system":"http://hl7.org/fhir/sid/cvx","code":"140"}]}}
                """)).isEmpty();
    }

    @Test
    void anEnteredInErrorImmunizationEmitsNoRows() {
        assertThat(explode("""
                {"resourceType":"Immunization","id":"1","status":"entered-in-error",
                 "vaccineCode":{"coding":[{"system":"http://hl7.org/fhir/sid/cvx","code":"140"}]}}
                """)).isEmpty();
    }

    @Test
    void aCompletedImmunizationEmitsOneRow() {
        assertThat(explode("""
                {"resourceType":"Immunization","id":"1","status":"completed",
                 "vaccineCode":{"coding":[{"system":"http://hl7.org/fhir/sid/cvx","code":"140"}]}}
                """)).hasSize(1);
    }

    /**
     * Builds the base row the way EcdsMapper does — shared columns first, then explode — so the
     * tests exercise the real composition rather than an empty starting row.
     */
    private List<EcdsRow> explode(String theJson) {
        JsonNode record = parse(theJson);
        EcdsRow base = new EcdsRow();
        String dos = DateOfService.of(record);
        base.set(EcdsColumn.DOS, dos);
        base.set(EcdsColumn.DOSThru, DateOfService.throughOf(record, dos));
        CodeColumns.applyTo(record, base);
        base.set(EcdsColumn.Ecdsaltid_00,
                record.path("resourceType").asText() + "/" + record.path("id").asText());
        return RowExploder.explode(record, base);
    }

    private JsonNode parse(String theJson) {
        try {
            return myMapper.readTree(theJson);
        } catch (Exception e) {
            throw new IllegalArgumentException(theJson, e);
        }
    }
}
