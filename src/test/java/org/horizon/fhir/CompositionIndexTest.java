package org.horizon.fhir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Maps a clinical resource back to the CCD document that carried it, for Ecdsaltid_01.
 */
class CompositionIndexTest {

    private final ObjectMapper myMapper = new ObjectMapper();
    private final CompositionIndex myIndex = new CompositionIndex();

    @Test
    void findsTheDocumentIdentifierForAReferencedResource() {
        myIndex.add(parse("""
                {"resourceType":"Composition","id":"2656",
                 "identifier":{"value":"urn:uuid:d240a6e9-e277-403d-8f9e-4622cceb4662"},
                 "section":[{"entry":[{"reference":"Condition/2678"}]}]}
                """));

        assertThat(myIndex.documentIdFor("Condition/2678"))
                .contains("urn:uuid:d240a6e9-e277-403d-8f9e-4622cceb4662");
    }

    @Test
    void isEmptyForAResourceNoCompositionReferences() {
        myIndex.add(parse("""
                {"resourceType":"Composition","id":"1","identifier":{"value":"urn:uuid:a"},
                 "section":[{"entry":[{"reference":"Condition/1"}]}]}
                """));

        assertThat(myIndex.documentIdFor("Observation/999")).isEmpty();
    }

    @Test
    void walksNestedSubsections() {
        // 46 of the export's references sit in a subsection rather than a top-level one.
        myIndex.add(parse("""
                {"resourceType":"Composition","id":"1","identifier":{"value":"urn:uuid:nested"},
                 "section":[{"title":"outer",
                             "section":[{"entry":[{"reference":"Observation/42"}]}]}]}
                """));

        assertThat(myIndex.documentIdFor("Observation/42")).contains("urn:uuid:nested");
    }

    @Test
    void indexesEveryEntryAcrossEverySection() {
        myIndex.add(parse("""
                {"resourceType":"Composition","id":"1","identifier":{"value":"urn:uuid:a"},
                 "section":[{"entry":[{"reference":"Condition/1"},{"reference":"Condition/2"}]},
                            {"entry":[{"reference":"Procedure/3"}]}]}
                """));

        assertThat(myIndex.documentIdFor("Condition/1")).contains("urn:uuid:a");
        assertThat(myIndex.documentIdFor("Condition/2")).contains("urn:uuid:a");
        assertThat(myIndex.documentIdFor("Procedure/3")).contains("urn:uuid:a");
    }

    @Test
    void acceptsAPlainIdentifierValueAsWellAsAUuid() {
        // One Composition in the export carries a bare id rather than a urn:uuid.
        myIndex.add(parse("""
                {"resourceType":"Composition","id":"3557",
                 "identifier":{"value":"9696E4A0-489E-11F1-A265-95F8447B3A12"},
                 "section":[{"entry":[{"reference":"Condition/1"}]}]}
                """));

        assertThat(myIndex.documentIdFor("Condition/1"))
                .contains("9696E4A0-489E-11F1-A265-95F8447B3A12");
    }

    @Test
    void theFirstCompositionToClaimAResourceWins() {
        myIndex.add(parse("""
                {"resourceType":"Composition","id":"1","identifier":{"value":"urn:uuid:first"},
                 "section":[{"entry":[{"reference":"Condition/1"}]}]}
                """));
        myIndex.add(parse("""
                {"resourceType":"Composition","id":"2","identifier":{"value":"urn:uuid:second"},
                 "section":[{"entry":[{"reference":"Condition/1"}]}]}
                """));

        assertThat(myIndex.documentIdFor("Condition/1")).contains("urn:uuid:first");
    }

    @Test
    void aCompositionWithNoIdentifierContributesNothing() {
        myIndex.add(parse("""
                {"resourceType":"Composition","id":"1",
                 "section":[{"entry":[{"reference":"Condition/1"}]}]}
                """));

        assertThat(myIndex.documentIdFor("Condition/1")).isEmpty();
    }

    @Test
    void theValueIsTheBareDocumentIdentifier() {
        // Horizon wants the identifier alone: no resource type, no search syntax.
        myIndex.add(parse("""
                {"resourceType":"Composition","id":"1","identifier":{"value":"urn:uuid:a"},
                 "section":[{"entry":[{"reference":"Condition/1"}]}]}
                """));

        assertThat(myIndex.documentIdFor("Condition/1"))
                .hasValueSatisfying(value -> assertThat(value).doesNotContain("Composition", "?", "="));
    }

    @Test
    void aResourceNoCompositionListsIsTracedThroughWhateverReferencesIt() {
        // Condition/2678 is in no Composition, but Encounter/2680 references it and that
        // Encounter is listed, so the Condition belongs to the same document.
        myIndex.add(parse("""
                {"resourceType":"Composition","id":"1","identifier":{"value":"urn:uuid:doc"},
                 "section":[{"entry":[{"reference":"Encounter/2680"}]}]}
                """));
        myIndex.addReferrer(parse("""
                {"resourceType":"Encounter","id":"2680",
                 "diagnosis":[{"condition":{"reference":"Condition/2678"}}]}
                """));

        myIndex.linkReferrers();

        assertThat(myIndex.documentIdFor("Condition/2678")).contains("urn:uuid:doc");
    }

    @Test
    void aDirectCompositionEntryWinsOverATracedOne() {
        myIndex.add(parse("""
                {"resourceType":"Composition","id":"1","identifier":{"value":"urn:uuid:direct"},
                 "section":[{"entry":[{"reference":"Condition/1"}]}]}
                """));
        myIndex.add(parse("""
                {"resourceType":"Composition","id":"2","identifier":{"value":"urn:uuid:other"},
                 "section":[{"entry":[{"reference":"Encounter/9"}]}]}
                """));
        myIndex.addReferrer(parse("""
                {"resourceType":"Encounter","id":"9",
                 "diagnosis":[{"condition":{"reference":"Condition/1"}}]}
                """));

        myIndex.linkReferrers();

        assertThat(myIndex.documentIdFor("Condition/1")).contains("urn:uuid:direct");
    }

    @Test
    void tracingIgnoresContainedReferences() {
        // "#id" is local to its parent and says nothing about which document carried it.
        myIndex.add(parse("""
                {"resourceType":"Composition","id":"1","identifier":{"value":"urn:uuid:doc"},
                 "section":[{"entry":[{"reference":"Observation/1"}]}]}
                """));
        myIndex.addReferrer(parse("""
                {"resourceType":"Observation","id":"1","hasMember":[{"reference":"#child"}]}
                """));

        myIndex.linkReferrers();

        assertThat(myIndex.documentIdFor("#child")).isEmpty();
    }

    @Test
    void theCompositionsOwnEncounterCountsAsOneOfItsResources() {
        // Composition.encounter names the visit the document describes. It is not a section
        // entry, but it is unambiguously part of that document.
        myIndex.add(parse("""
                {"resourceType":"Composition","id":"3154","identifier":{"value":"urn:uuid:doc"},
                 "encounter":{"reference":"Encounter/3162"},
                 "section":[{"entry":[{"reference":"Condition/1"}]}]}
                """));

        assertThat(myIndex.documentIdFor("Encounter/3162")).contains("urn:uuid:doc");
    }

    @Test
    void aResourceReachedThroughTheCompositionsEncounterIsTraced() {
        // Condition/3221 sits in no section and is referenced only by Encounter/3162,
        // which is the Composition's own encounter.
        myIndex.add(parse("""
                {"resourceType":"Composition","id":"3154","identifier":{"value":"urn:uuid:doc"},
                 "encounter":{"reference":"Encounter/3162"},"section":[]}
                """));
        myIndex.addReferrer(parse("""
                {"resourceType":"Condition","id":"3221",
                 "encounter":{"reference":"Encounter/3162"}}
                """));
        myIndex.addReferrer(parse("""
                {"resourceType":"Encounter","id":"3162"}
                """));

        myIndex.linkReferrers();

        assertThat(myIndex.documentIdFor("Condition/3221")).contains("urn:uuid:doc");
    }

    @Test
    void aResourceNothingReferencesHasNoProvenance() {
        myIndex.add(parse("""
                {"resourceType":"Composition","id":"1","identifier":{"value":"urn:uuid:doc"},
                 "section":[{"entry":[{"reference":"Condition/1"}]}]}
                """));

        myIndex.linkReferrers();

        assertThat(myIndex.documentIdFor("Condition/999")).isEmpty();
    }

    @Test
    void anAmbiguousResourceCitedBySeveralDocumentsIsNotTraced() {
        // Practitioners and Patients are shared across documents, so no single one is
        // their source. Guessing would attribute a row to the wrong document.
        myIndex.add(parse("""
                {"resourceType":"Composition","id":"1","identifier":{"value":"urn:uuid:a"},
                 "section":[{"entry":[{"reference":"Condition/1"}]}]}
                """));
        myIndex.add(parse("""
                {"resourceType":"Composition","id":"2","identifier":{"value":"urn:uuid:b"},
                 "section":[{"entry":[{"reference":"Condition/2"}]}]}
                """));
        myIndex.addReferrer(parse("""
                {"resourceType":"Condition","id":"1","asserter":{"reference":"Practitioner/p"}}
                """));
        myIndex.addReferrer(parse("""
                {"resourceType":"Condition","id":"2","asserter":{"reference":"Practitioner/p"}}
                """));

        myIndex.linkReferrers();

        assertThat(myIndex.documentIdFor("Practitioner/p")).isEmpty();
    }

    @Test
    void reportsTheEncounterOfTheDocumentThatCarriedAResource() {
        // A Procedure names no encounter of its own, but the document that carried it
        // describes one visit, which is where its provider is recorded.
        myIndex.add(parse("""
                {"resourceType":"Composition","id":"3154","identifier":{"value":"urn:uuid:doc"},
                 "encounter":{"reference":"Encounter/3162"},
                 "section":[{"entry":[{"reference":"Procedure/1"}]}]}
                """));

        assertThat(myIndex.encounterOfDocumentFor("Procedure/1")).contains("Encounter/3162");
    }

    @Test
    void reportsNoDocumentEncounterWhenTheCompositionNamesNone() {
        myIndex.add(parse("""
                {"resourceType":"Composition","id":"1","identifier":{"value":"urn:uuid:doc"},
                 "section":[{"entry":[{"reference":"Procedure/1"}]}]}
                """));

        assertThat(myIndex.encounterOfDocumentFor("Procedure/1")).isEmpty();
    }

    @Test
    void reportsNoDocumentEncounterForAnUnknownResource() {
        assertThat(myIndex.encounterOfDocumentFor("Procedure/999")).isEmpty();
    }

    @Test
    void nonCompositionResourcesAreIgnored() {
        myIndex.add(parse("{\"resourceType\":\"Observation\",\"id\":\"1\"}"));

        assertThat(myIndex.size()).isZero();
    }

    @Test
    void isEmptyForANullOrBlankReference() {
        assertThat(myIndex.documentIdFor(null)).isEmpty();
        assertThat(myIndex.documentIdFor("  ")).isEmpty();
    }

    /**
     * The referrer map is only consulted by {@link CompositionIndex#linkReferrers()}, so holding
     * it afterwards costs heap for nothing. On a 5000-CCD export it measures around 179 MB, and
     * the mapper's second pass runs after linking — so it is released before the pass that needs
     * the headroom.
     */
    @Test
    void releasesTheReferrerMapOnceLinkingIsDone() {
        myIndex.add(parse("{\"resourceType\":\"Composition\","
                + "\"identifier\":{\"value\":\"doc-1\"},"
                + "\"section\":[{\"entry\":[{\"reference\":\"Encounter/9\"}]}]}"));
        myIndex.addReferrer(parse("{\"resourceType\":\"Encounter\",\"id\":\"9\","
                + "\"subject\":{\"reference\":\"Patient/4\"}}"));

        assertThat(myIndex.referrerCount()).isEqualTo(1);

        myIndex.linkReferrers();

        // The trace still resolved — the map is freed, not skipped.
        assertThat(myIndex.documentIdFor("Patient/4")).contains("doc-1");
        assertThat(myIndex.referrerCount()).isZero();
    }

    private JsonNode parse(String theJson) {
        try {
            return myMapper.readTree(theJson);
        } catch (Exception e) {
            throw new IllegalArgumentException(theJson, e);
        }
    }
}
