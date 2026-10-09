package org.horizon.ecds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The feed is carried per resource on meta.tag, so a single export can mix origins.
 */
class DataOriginTest {

    private static final String TAG_SYSTEM = "http://horizonblue.com/fhir/meta/tag/data-origin";

    private final ObjectMapper myMapper = new ObjectMapper();

    @Test
    void athenaHealthResolvesToEhr() {
        JsonNode resource = taggedWith("AthenaHealth");

        assertThat(DataOrigin.of(resource).clientSystemOfRecordId()).isEqualTo("EHR");
    }

    @Test
    void konzaHealthResolvesToHie() {
        JsonNode resource = taggedWith("KonzaHealth");

        assertThat(DataOrigin.of(resource).clientSystemOfRecordId()).isEqualTo("HIE");
    }

    @Test
    void theTwoFeedColumnsAlwaysAgree() {
        for (String code : new String[] {"AthenaHealth", "KonzaHealth"}) {
            FeedConstants constants = DataOrigin.of(taggedWith(code));
            assertThat(constants.datasourcename()).isEqualTo(constants.clientSystemOfRecordId());
        }
    }

    @Test
    void theOriginCodeIsMatchedCaseInsensitively() {
        // The export writes KONZAHealth, not KonzaHealth. Matching the literal spelling
        // silently mislabelled every Konza row as EHR.
        assertThat(DataOrigin.of(taggedWith("KONZAHealth")).clientSystemOfRecordId()).isEqualTo("HIE");
        assertThat(DataOrigin.of(taggedWith("konzahealth")).clientSystemOfRecordId()).isEqualTo("HIE");
        assertThat(DataOrigin.of(taggedWith("ATHENAHEALTH")).clientSystemOfRecordId()).isEqualTo("EHR");
    }

    @Test
    void theTagIsMatchedOnItsSystemNotJustItsCode() {
        // A code that happens to match under a different system must not be honoured.
        JsonNode resource = parse("""
                {"resourceType":"Observation","id":"1","meta":{"tag":[
                  {"system":"http://example.org/other","code":"KonzaHealth"}]}}
                """);

        assertThat(DataOrigin.of(resource)).isNull();
    }

    @Test
    void anUntaggedResourceHasNoOrigin() {
        JsonNode resource = parse("{\"resourceType\":\"Observation\",\"id\":\"1\"}");

        assertThat(DataOrigin.of(resource)).isNull();
    }

    @Test
    void anUnrecognisedOriginCodeIsNotGuessedAt() {
        JsonNode resource = taggedWith("SomeOtherHealth");

        assertThat(DataOrigin.of(resource)).isNull();
    }

    @Test
    void theOriginTagIsFoundAlongsideOtherTags() {
        JsonNode resource = parse("""
                {"resourceType":"Observation","id":"1","meta":{"tag":[
                  {"system":"http://example.org/other","code":"whatever"},
                  {"system":"%s","code":"KonzaHealth"}]}}
                """.formatted(TAG_SYSTEM));

        assertThat(DataOrigin.of(resource).clientSystemOfRecordId()).isEqualTo("HIE");
    }

    @Test
    void referenceIdAndSuppSourceDoNotVaryByOrigin() {
        // ReferenceID is the measurement-year constant; SuppSource is a blanket H pending
        // Horizon's answer. Neither is feed-derived.
        FeedConstants athena = DataOrigin.of(taggedWith("AthenaHealth"));
        FeedConstants konza = DataOrigin.of(taggedWith("KonzaHealth"));

        assertThat(athena.referenceId()).isEqualTo("SMILEMY26");
        assertThat(konza.referenceId()).isEqualTo("SMILEMY26");
        assertThat(athena.suppSource()).isEqualTo("H");
        assertThat(konza.suppSource()).isEqualTo("H");
    }

    private JsonNode taggedWith(String theCode) {
        return parse("""
                {"resourceType":"Observation","id":"1","meta":{"tag":[
                  {"system":"%s","code":"%s"}]}}
                """.formatted(TAG_SYSTEM, theCode));
    }

    private JsonNode parse(String theJson) {
        try {
            return myMapper.readTree(theJson);
        } catch (Exception e) {
            throw new IllegalArgumentException(theJson, e);
        }
    }
}
