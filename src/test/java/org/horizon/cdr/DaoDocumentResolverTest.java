package org.horizon.cdr;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Reference;
import org.horizon.fhir.DocumentResolver.SourceDocument;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class DaoDocumentResolverTest {

    private final FhirContext myContext = FhirContext.forR4Cached();
    private final Supplier<RequestDetails> myRequest = SystemRequestDetails::new;
    private final ObjectMapper myJson = new ObjectMapper();

    @Test
    void aRecordListedInASectionBelongsToThatDocument() throws Exception {
        FakeStorage storage = new FakeStorage(myContext)
                .add(composition("c1", "doc-001", "Encounter/9", "Condition/1"));
        DaoDocumentResolver resolver = new DaoDocumentResolver(storage.registry(), myRequest, 10);

        Optional<SourceDocument> found = resolver.documentFor(record("Condition", "1", null));

        assertThat(found).contains(new SourceDocument("doc-001", Optional.of("Encounter/9")));
    }

    @Test
    void anUnlistedRecordIsReachedThroughTheVisitTheDocumentDescribes() throws Exception {
        FakeStorage storage = new FakeStorage(myContext)
                .add(composition("c1", "doc-001", "Encounter/9", "Procedure/5"));
        DaoDocumentResolver resolver = new DaoDocumentResolver(storage.registry(), myRequest, 10);

        assertThat(resolver.documentFor(record("Condition", "2", "Encounter/9")))
                .map(SourceDocument::identifier).contains("doc-001");
        assertThat(resolver.documentFor(record("Encounter", "9", null)))
                .map(SourceDocument::identifier).contains("doc-001");
    }

    @Test
    void anUnlistedRecordIsReachedThroughAVisitListedInASection() throws Exception {
        FakeStorage storage = new FakeStorage(myContext)
                .add(composition("c1", "doc-001", null, "Encounter/9"));
        DaoDocumentResolver resolver = new DaoDocumentResolver(storage.registry(), myRequest, 10);

        assertThat(resolver.documentFor(record("Observation", "3", "Encounter/9")))
                .contains(new SourceDocument("doc-001", Optional.empty()));
    }

    @Test
    void aRecordTwoDocumentsCouldClaimIsLeftUnassigned() throws Exception {
        FakeStorage storage = new FakeStorage(myContext)
                .add(composition("c1", "doc-001", "Encounter/9"))
                .add(composition("c2", "doc-002", "Encounter/9"));
        DaoDocumentResolver resolver = new DaoDocumentResolver(storage.registry(), myRequest, 10);

        assertThat(resolver.documentFor(record("Condition", "2", "Encounter/9"))).isEmpty();
    }

    @Test
    void theSameDocumentListedTwiceIsStillOneDocument() throws Exception {
        // Two Compositions carrying the same identifier (a re-sent CCD) are one source document.
        FakeStorage storage = new FakeStorage(myContext)
                .add(composition("c1", "doc-001", "Encounter/9", "Condition/1"))
                .add(composition("c2", "doc-001", "Encounter/9", "Condition/1"));
        DaoDocumentResolver resolver = new DaoDocumentResolver(storage.registry(), myRequest, 10);

        assertThat(resolver.documentFor(record("Condition", "1", null)))
                .map(SourceDocument::identifier).contains("doc-001");
    }

    @Test
    void aCompositionWithoutAnIdentifierIsIgnored() throws Exception {
        FakeStorage storage = new FakeStorage(myContext)
                .add(composition("c1", null, "Encounter/9", "Condition/1"));
        DaoDocumentResolver resolver = new DaoDocumentResolver(storage.registry(), myRequest, 10);

        assertThat(resolver.documentFor(record("Condition", "1", "Encounter/9"))).isEmpty();
    }

    @Test
    void recordsSharingAnEncounterShareOneLookup() throws Exception {
        FakeStorage storage = new FakeStorage(myContext)
                .add(composition("c1", "doc-001", "Encounter/9"));
        DaoDocumentResolver resolver = new DaoDocumentResolver(storage.registry(), myRequest, 10);

        resolver.documentFor(record("Condition", "1", "Encounter/9"));
        long afterFirst = resolver.searches();
        resolver.documentFor(record("Condition", "2", "Encounter/9"));
        resolver.documentFor(record("Observation", "3", "Encounter/9"));

        // Each new record costs one direct-listing search; the encounter route is answered
        // from cache after the first time.
        assertThat(resolver.searches() - afterFirst).isEqualTo(2);
    }

    // --- the member's own documents, walked in Java -----------------------------------------

    @Test
    void aRecordListedInANestedSectionBelongsToThatDocument() throws Exception {
        // The R4 Composition-entry search parameter is Composition.section.entry and never sees
        // this entry. The CLI index recursed, and Ecdsaltid_01 is required, so this has to work.
        Composition composition = composition("c1", "doc-001", "Encounter/9");
        composition.setSubject(new Reference("Patient/p1"));
        composition.getSection().get(0).addSection().addEntry(new Reference("Condition/1"));
        DaoDocumentResolver resolver = new DaoDocumentResolver(
                new FakeStorage(myContext).add(composition).registry(), myRequest, 10);

        assertThat(resolver.documentFor(memberRecord("Condition", "1", null)))
                .contains(new SourceDocument("doc-001", Optional.of("Encounter/9")));
    }

    @Test
    void aRecordThreeSectionsDeepIsStillFound() throws Exception {
        Composition composition = composition("c1", "doc-001", null);
        composition.setSubject(new Reference("Patient/p1"));
        composition.getSection().get(0).addSection().addSection().addEntry(new Reference("Observation/7"));
        DaoDocumentResolver resolver = new DaoDocumentResolver(
                new FakeStorage(myContext).add(composition).registry(), myRequest, 10);

        assertThat(resolver.documentFor(memberRecord("Observation", "7", null)))
                .map(SourceDocument::identifier).contains("doc-001");
    }

    @Test
    void anAbsoluteSectionEntryMatchesTheRelativeRecord() throws Exception {
        Composition composition = composition("c1", "doc-001", null,
                "http://example.org/fhir/Condition/1");
        composition.setSubject(new Reference("Patient/p1"));
        DaoDocumentResolver resolver = new DaoDocumentResolver(
                new FakeStorage(myContext).add(composition).registry(), myRequest, 10);

        assertThat(resolver.documentFor(memberRecord("Condition", "1", null)))
                .map(SourceDocument::identifier).contains("doc-001");
    }

    @Test
    void theFirstOfTwoDocumentsListingTheRecordWins() throws Exception {
        // The CLI index kept the first document it saw rather than blanking a required column,
        // and the member index does the same, ordered so two runs agree.
        Composition first = composition("c1", "doc-001", null, "Condition/1");
        first.setSubject(new Reference("Patient/p1"));
        Composition second = composition("c2", "doc-002", null, "Condition/1");
        second.setSubject(new Reference("Patient/p1"));
        DaoDocumentResolver resolver = new DaoDocumentResolver(
                new FakeStorage(myContext).add(first).add(second).registry(), myRequest, 10);

        assertThat(resolver.documentFor(memberRecord("Condition", "1", null)))
                .map(SourceDocument::identifier).contains("doc-001");
    }

    @Test
    void aMembersDocumentsAreIndexedOnceForAllOfTheirRecords() throws Exception {
        Composition composition = composition("c1", "doc-001", "Encounter/9",
                "Condition/1", "Observation/2", "Procedure/3");
        composition.setSubject(new Reference("Patient/p1"));
        DaoDocumentResolver resolver = new DaoDocumentResolver(
                new FakeStorage(myContext).add(composition).registry(), myRequest, 10);

        resolver.documentFor(memberRecord("Condition", "1", null));
        resolver.documentFor(memberRecord("Observation", "2", null));
        resolver.documentFor(memberRecord("Procedure", "3", null));

        // One Composition?patient= search for the member, and nothing per record.
        assertThat(resolver.searches()).isEqualTo(1);
    }

    @Test
    void aRecordInNoneOfTheMembersDocumentsStillFallsBackToTheSearches() throws Exception {
        // The document names a different subject, so the member index cannot see it.
        Composition composition = composition("c1", "doc-001", null, "Condition/1");
        composition.setSubject(new Reference("Patient/someone-else"));
        DaoDocumentResolver resolver = new DaoDocumentResolver(
                new FakeStorage(myContext).add(composition).registry(), myRequest, 10);

        assertThat(resolver.documentFor(memberRecord("Condition", "1", null)))
                .map(SourceDocument::identifier).contains("doc-001");
    }

    private JsonNode record(String theType, String theId, String theEncounter) throws Exception {
        return record(theType, theId, theEncounter, null);
    }

    /** A record that names its member, so the member-document index applies. */
    private JsonNode memberRecord(String theType, String theId, String theEncounter) throws Exception {
        return record(theType, theId, theEncounter, "Patient/p1");
    }

    private JsonNode record(String theType, String theId, String theEncounter, String theSubject)
            throws Exception {
        String encounter = theEncounter == null ? "" : ",\"encounter\":{\"reference\":\"" + theEncounter + "\"}";
        String subject = theSubject == null ? "" : ",\"subject\":{\"reference\":\"" + theSubject + "\"}";
        return myJson.readTree("{\"resourceType\":\"" + theType + "\",\"id\":\"" + theId + "\""
                + encounter + subject + "}");
    }

    private static Composition composition(String theId, String theIdentifier, String theEncounter,
                                           String... theEntries) {
        Composition composition = new Composition();
        composition.setId("Composition/" + theId);
        if (theIdentifier != null) {
            composition.setIdentifier(new Identifier().setValue(theIdentifier));
        }
        if (theEncounter != null) {
            composition.setEncounter(new Reference(theEncounter));
        }
        Composition.SectionComponent section = composition.addSection();
        for (String entry : theEntries) {
            section.addEntry(new Reference(entry));
        }
        return composition;
    }
}
