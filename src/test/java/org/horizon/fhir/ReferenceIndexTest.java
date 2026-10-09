package org.horizon.fhir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceIndexTest {

    private final ObjectMapper myMapper = new ObjectMapper();
    private final ReferenceIndex myIndex = new ReferenceIndex();

    @Test
    void resolvesAnIndexedResourceByTypeAndId() {
        myIndex.add(parse("{\"resourceType\":\"Patient\",\"id\":\"2518\",\"gender\":\"female\"}"));

        assertThat(myIndex.resolve("Patient/2518")).isPresent();
        assertThat(myIndex.resolve("Patient/2518").get().path("gender").asText()).isEqualTo("female");
    }

    @Test
    void isEmptyForAnUnknownReference() {
        assertThat(myIndex.resolve("Patient/9999")).isEmpty();
    }

    @Test
    void isEmptyForANullOrBlankReference() {
        assertThat(myIndex.resolve(null)).isEmpty();
        assertThat(myIndex.resolve("  ")).isEmpty();
    }

    @Test
    void onlyReferenceableTypesAreIndexed() {
        // Observations are streamed, never looked up, so indexing them would waste memory.
        myIndex.add(parse("{\"resourceType\":\"Observation\",\"id\":\"1\"}"));
        myIndex.add(parse("{\"resourceType\":\"Patient\",\"id\":\"2\"}"));

        assertThat(myIndex.resolve("Observation/1")).isEmpty();
        assertThat(myIndex.resolve("Patient/2")).isPresent();
        assertThat(myIndex.size()).isEqualTo(1);
    }

    @Test
    void indexesPractitionerAndEncounter() {
        myIndex.add(parse("{\"resourceType\":\"Practitioner\",\"id\":\"2526\"}"));
        myIndex.add(parse("{\"resourceType\":\"Encounter\",\"id\":\"2546\"}"));

        assertThat(myIndex.resolve("Practitioner/2526")).isPresent();
        assertThat(myIndex.resolve("Encounter/2546")).isPresent();
    }

    @Test
    void aRecordWithNoIdIsIgnored() {
        myIndex.add(parse("{\"resourceType\":\"Patient\"}"));

        assertThat(myIndex.size()).isZero();
    }

    @Test
    void resolvesAContainedReferenceAgainstItsParent() {
        // hasMember on the sample panels points at "#id", unique only within the parent.
        JsonNode parent = parse("""
                {"resourceType":"Observation","id":"2678",
                 "contained":[{"resourceType":"Observation","id":"child-1","valueInteger":1}]}
                """);

        assertThat(myIndex.resolve("#child-1", parent)).isPresent();
        assertThat(myIndex.resolve("#child-1", parent).get().path("valueInteger").asInt()).isEqualTo(1);
    }

    @Test
    void aContainedReferenceIsNotResolvedFromTheGlobalIndex() {
        JsonNode other = parse("""
                {"resourceType":"Observation","id":"other",
                 "contained":[{"resourceType":"Observation","id":"child-1"}]}
                """);
        JsonNode parent = parse("{\"resourceType\":\"Observation\",\"id\":\"2678\"}");
        myIndex.add(other);

        assertThat(myIndex.resolve("#child-1", parent)).isEmpty();
    }

    @Test
    void aContainedReferenceWithNoContainingResourceIsUnresolved() {
        assertThat(myIndex.resolve("#child-1", null)).isEmpty();
    }

    @Test
    void recordsAMissWhenAReferenceCannotBeResolved() {
        myIndex.resolve("Patient/9999");
        myIndex.resolve("Patient/8888");
        myIndex.resolve("Patient/9999");

        assertThat(myIndex.unresolvedReferences()).containsExactlyInAnyOrder("Patient/9999", "Patient/8888");
    }

    @Test
    void aSuccessfulResolveIsNotRecordedAsAMiss() {
        myIndex.add(parse("{\"resourceType\":\"Patient\",\"id\":\"2518\"}"));

        myIndex.resolve("Patient/2518");

        assertThat(myIndex.unresolvedReferences()).isEmpty();
    }

    private JsonNode parse(String theJson) {
        try {
            return myMapper.readTree(theJson);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
