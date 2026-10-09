package org.horizon.ecds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.horizon.fhir.ReferenceIndex;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DateOfServiceTest {

    private final ObjectMapper myMapper = new ObjectMapper();

    // --- precedence per resource type -------------------------------------

    @Test
    void observationPrefersEffectiveDateTime() {
        JsonNode observation = parse("""
                {"resourceType":"Observation","effectiveDateTime":"2026-06-05",
                 "effectivePeriod":{"start":"2020-01-01"},"issued":"2019-01-01T00:00:00Z"}
                """);

        assertThat(DateOfService.of(observation)).isEqualTo("2026-06-05");
    }

    @Test
    void observationFallsBackToEffectivePeriodStartThenIssued() {
        assertThat(DateOfService.of(parse("""
                {"resourceType":"Observation","effectivePeriod":{"start":"2026-06-05"},
                 "issued":"2019-01-01T00:00:00Z"}
                """))).isEqualTo("2026-06-05");

        assertThat(DateOfService.of(parse("""
                {"resourceType":"Observation","issued":"2019-03-04T12:00:00-05:00"}
                """))).isEqualTo("2019-03-04");
    }

    @Test
    void procedurePrefersPerformedDateTimeThenPeriodStart() {
        assertThat(DateOfService.of(parse("""
                {"resourceType":"Procedure","performedDateTime":"2026-05-05"}
                """))).isEqualTo("2026-05-05");

        assertThat(DateOfService.of(parse("""
                {"resourceType":"Procedure","performedPeriod":{"start":"2025-03-04"}}
                """))).isEqualTo("2025-03-04");
    }

    @Test
    void conditionPrefersOnsetThenRecordedDate() {
        assertThat(DateOfService.of(parse("""
                {"resourceType":"Condition","onsetDateTime":"2026-06-05T09:01:19-04:00",
                 "recordedDate":"2025-06-05T13:00:25-05:00"}
                """))).isEqualTo("2026-06-05");

        assertThat(DateOfService.of(parse("""
                {"resourceType":"Condition","recordedDate":"2025-06-05T13:00:25-05:00"}
                """))).isEqualTo("2025-06-05");
    }

    @Test
    void encounterUsesPeriodStart() {
        assertThat(DateOfService.of(parse("""
                {"resourceType":"Encounter","period":{"start":"2026-06-05"}}
                """))).isEqualTo("2026-06-05");
    }

    @Test
    void immunizationUsesOccurrenceDateTime() {
        assertThat(DateOfService.of(parse("""
                {"resourceType":"Immunization","occurrenceDateTime":"2025-06-03"}
                """))).isEqualTo("2025-06-03");
    }

    @Test
    void medicationRequestUsesAuthoredOn() {
        assertThat(DateOfService.of(parse("""
                {"resourceType":"MedicationRequest","authoredOn":"2025-06-09T16:27:42-05:00"}
                """))).isEqualTo("2025-06-09");
    }

    // --- normalisation ------------------------------------------------------

    @Test
    void anInstantIsConvertedToTheNewYorkLocalDate() {
        // 03:30 UTC is still the previous evening in New York, so the date moves back a day.
        JsonNode observation = parse("""
                {"resourceType":"Observation","effectiveDateTime":"2026-06-06T03:30:00Z"}
                """);

        assertThat(DateOfService.of(observation)).isEqualTo("2026-06-05");
    }

    @Test
    void anOffsetDateTimeIsConvertedToTheNewYorkLocalDate() {
        // 23:00 Pacific on the 5th is 02:00 Eastern on the 6th.
        JsonNode observation = parse("""
                {"resourceType":"Observation","effectiveDateTime":"2026-06-05T23:00:00-07:00"}
                """);

        assertThat(DateOfService.of(observation)).isEqualTo("2026-06-06");
    }

    @Test
    void aDateOnlyValueIsNotTimezoneShifted() {
        // A plain date carries no timezone; converting it could move it a day.
        JsonNode observation = parse("""
                {"resourceType":"Observation","effectiveDateTime":"2026-06-05"}
                """);

        assertThat(DateOfService.of(observation)).isEqualTo("2026-06-05");
    }

    @Test
    void aDateTimeWithNoOffsetIsReadAsLocalAndNotRejected() {
        JsonNode observation = parse("""
                {"resourceType":"Observation","effectiveDateTime":"2026-06-07T00:00:00"}
                """);

        assertThat(DateOfService.of(observation)).isEqualTo("2026-06-07");
    }

    @Test
    void aPartialDateIsNotEmitted() {
        assertThat(DateOfService.of(parse("""
                {"resourceType":"Observation","effectiveDateTime":"2026-06"}
                """))).isEmpty();
    }

    @Test
    void anUnparseableDateIsNotEmitted() {
        assertThat(DateOfService.of(parse("""
                {"resourceType":"Observation","effectiveDateTime":"not-a-date"}
                """))).isEmpty();
    }

    // --- the source document's own period -----------------------------------

    @Test
    void diagnosticReportUsesEffectivePeriodStart() {
        // Every DiagnosticReport in the export carries this and no effectiveDateTime, so the
        // date was being discarded rather than genuinely absent.
        JsonNode report = parse("""
                {"resourceType":"DiagnosticReport",
                 "effectivePeriod":{"start":"2025-06-04T20:00:00-04:00"},
                 "_issued":{"extension":[{"url":"http://hl7.org/fhir/StructureDefinition/data-absent-reason",
                                          "valueCode":"unknown"}]}}
                """);

        assertThat(DateOfService.of(report)).isEqualTo("2025-06-04");
    }

    // --- falling back to the encounter --------------------------------------

    @Test
    void aRecordWithNoDateOfItsOwnUsesItsEncounterDate() {
        // 281 Conditions in the export carry only a code and an encounter.
        ReferenceIndex index = indexWith("""
                {"resourceType":"Encounter","id":"2640","period":{"start":"2026-04-03"}}
                """);
        JsonNode condition = parse("""
                {"resourceType":"Condition","id":"1","encounter":{"reference":"Encounter/2640"}}
                """);

        assertThat(DateOfService.of(condition, index)).isEqualTo("2026-04-03");
    }

    @Test
    void aRecordWithItsOwnDateIgnoresTheEncounterDate() {
        // The guard that matters. Onset is when a condition began, not when it was recorded:
        // across the export these disagree by a median of 89 days and by up to 17 years.
        ReferenceIndex index = indexWith("""
                {"resourceType":"Encounter","id":"9","period":{"start":"2026-04-03"}}
                """);
        JsonNode condition = parse("""
                {"resourceType":"Condition","id":"1","onsetDateTime":"2009-01-15",
                 "encounter":{"reference":"Encounter/9"}}
                """);

        assertThat(DateOfService.of(condition, index)).isEqualTo("2009-01-15");
    }

    @Test
    void aRecordWithNoDateAndNoEncounterYieldsNothing() {
        assertThat(DateOfService.of(parse("{\"resourceType\":\"Condition\",\"id\":\"1\"}"),
                new ReferenceIndex())).isEmpty();
    }

    @Test
    void anEncounterWithNoPeriodStartYieldsNothing() {
        ReferenceIndex index = indexWith("{\"resourceType\":\"Encounter\",\"id\":\"9\"}");
        JsonNode condition = parse("""
                {"resourceType":"Condition","id":"1","encounter":{"reference":"Encounter/9"}}
                """);

        assertThat(DateOfService.of(condition, index)).isEmpty();
    }

    @Test
    void anUnresolvableEncounterYieldsNothing() {
        JsonNode condition = parse("""
                {"resourceType":"Condition","id":"1","encounter":{"reference":"Encounter/missing"}}
                """);

        assertThat(DateOfService.of(condition, new ReferenceIndex())).isEmpty();
    }

    @Test
    void dosThruUsesTheEncounterPeriodEndWhenDosCameFromTheEncounter() {
        ReferenceIndex index = indexWith("""
                {"resourceType":"Encounter","id":"9","period":{"start":"2026-04-03","end":"2026-04-05"}}
                """);
        JsonNode condition = parse("""
                {"resourceType":"Condition","id":"1","encounter":{"reference":"Encounter/9"}}
                """);

        assertThat(DateOfService.throughOf(condition, "2026-04-03", index)).isEqualTo("2026-04-05");
    }

    @Test
    void dosThruStillPrefersTheRecordsOwnAbatementDate() {
        ReferenceIndex index = indexWith("""
                {"resourceType":"Encounter","id":"9","period":{"start":"2026-04-03","end":"2026-04-05"}}
                """);
        JsonNode condition = parse("""
                {"resourceType":"Condition","id":"1","onsetDateTime":"2026-04-01",
                 "abatementDateTime":"2026-04-02","encounter":{"reference":"Encounter/9"}}
                """);

        assertThat(DateOfService.throughOf(condition, "2026-04-01", index)).isEqualTo("2026-04-02");
    }

    @Test
    void dosThruIgnoresTheEncounterWhenDosCameFromTheRecord() {
        // Immunization/3458 in the export: occurrence 2015, encounter ending 2024. Taking the
        // end from the encounter while the start came from the record invents a 9-year span.
        ReferenceIndex index = indexWith("""
                {"resourceType":"Encounter","id":"9","period":{"start":"2024-09-10","end":"2024-09-10"}}
                """);
        JsonNode immunization = parse("""
                {"resourceType":"Immunization","id":"1","occurrenceDateTime":"2015-09-22",
                 "encounter":{"reference":"Encounter/9"}}
                """);

        assertThat(DateOfService.throughOf(immunization, "2015-09-22", index)).isEqualTo("2015-09-22");
    }

    @Test
    void dosThruNeverPrecedesDos() {
        // Condition/3474 in the export: an abatement in 2015, no onset, and a 2024 encounter.
        // Inheriting the encounter start would end the row nine years before it began.
        ReferenceIndex index = indexWith("""
                {"resourceType":"Encounter","id":"9","period":{"start":"2024-09-10"}}
                """);
        JsonNode condition = parse("""
                {"resourceType":"Condition","id":"1","abatementDateTime":"2015-07-08",
                 "encounter":{"reference":"Encounter/9"}}
                """);

        String dos = DateOfService.of(condition, index);
        String through = DateOfService.throughOf(condition, dos, index);

        assertThat(through).isGreaterThanOrEqualTo(dos);
    }

    private ReferenceIndex indexWith(String theEncounterJson) {
        ReferenceIndex index = new ReferenceIndex();
        index.add(parse(theEncounterJson));
        return index;
    }

    // --- absence ------------------------------------------------------------

    @Test
    void aRecordWithNoDateYieldsAnEmptyDos() {
        assertThat(DateOfService.of(parse("{\"resourceType\":\"Observation\"}"))).isEmpty();
    }

    @Test
    void aDiagnosticReportWhoseIssuedIsDataAbsentYieldsAnEmptyDos() {
        // Every DiagnosticReport in the export is in this shape.
        JsonNode report = parse("""
                {"resourceType":"DiagnosticReport",
                 "_issued":{"extension":[{"url":"http://hl7.org/fhir/StructureDefinition/data-absent-reason",
                                          "valueCode":"unknown"}]}}
                """);

        assertThat(DateOfService.of(report)).isEmpty();
    }

    // --- DOSThru ------------------------------------------------------------

    @Test
    void dosThruEqualsDosWhenThereIsNoPeriodEnd() {
        JsonNode observation = parse("""
                {"resourceType":"Observation","effectiveDateTime":"2026-06-05"}
                """);

        assertThat(DateOfService.throughOf(observation, "2026-06-05")).isEqualTo("2026-06-05");
    }

    @Test
    void dosThruUsesEffectivePeriodEndWhenPresent() {
        JsonNode observation = parse("""
                {"resourceType":"Observation","effectivePeriod":{"start":"2026-06-05","end":"2026-06-09"}}
                """);

        assertThat(DateOfService.throughOf(observation, "2026-06-05")).isEqualTo("2026-06-09");
    }

    @Test
    void dosThruUsesEncounterPeriodEnd() {
        JsonNode encounter = parse("""
                {"resourceType":"Encounter","period":{"start":"2026-06-05","end":"2026-06-07"}}
                """);

        assertThat(DateOfService.throughOf(encounter, "2026-06-05")).isEqualTo("2026-06-07");
    }

    @Test
    void dosThruUsesConditionAbatement() {
        JsonNode condition = parse("""
                {"resourceType":"Condition","onsetDateTime":"2026-06-05T09:01:19-04:00",
                 "abatementDateTime":"2026-06-05T09:08:20-04:00"}
                """);

        assertThat(DateOfService.throughOf(condition, "2026-06-05")).isEqualTo("2026-06-05");
    }

    @Test
    void dosThruIsEmptyWhenDosIsEmptyAndThereIsNoPeriodEnd() {
        // No sentinel date is ever written.
        assertThat(DateOfService.throughOf(parse("{\"resourceType\":\"Observation\"}"), "")).isEmpty();
    }

    private JsonNode parse(String theJson) {
        try {
            return myMapper.readTree(theJson);
        } catch (Exception e) {
            throw new IllegalArgumentException(theJson, e);
        }
    }
}
