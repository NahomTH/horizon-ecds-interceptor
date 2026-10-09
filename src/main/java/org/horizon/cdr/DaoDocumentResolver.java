package org.horizon.cdr;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Reference;
import org.horizon.fhir.DocumentResolver;
import org.horizon.fhir.FhirPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Finds the source document (CCD) a record arrived in, for the required {@code Ecdsaltid_01}
 * column, by reading Compositions out of the CDR.
 *
 * <p>Replaces the CLI's {@code CompositionIndex}, which needed every Composition in the export
 * held in memory and a global linking pass. Here the same question is answered a member at a
 * time:
 *
 * <ol>
 *   <li><b>The member's own documents.</b> One search — {@code Composition?patient=Patient/x} —
 *       and its sections are walked <em>in Java, recursively</em>, exactly as
 *       {@code CompositionIndex.indexSections} walked them: the first document to list a
 *       resource claims it. The document's own {@code encounter} is registered the same way, so
 *       a record that no section lists but whose visit a document describes is still attributed.
 *       This covers essentially every row and costs one query per member rather than one to
 *       three per record.</li>
 *   <li><b>Reverse searches</b>, for the rest: {@code Composition?entry=Type/id}, then
 *       {@code Composition?encounter=...} / {@code Composition?entry=...} for the record's
 *       encounter. These reach a document that does not name this member as its subject, which
 *       the first step cannot see.</li>
 * </ol>
 *
 * <p><b>Why step 1 is not just another {@code Composition?entry=} search.</b> The R4
 * {@code Composition-entry} search parameter is defined as {@code Composition.section.entry} —
 * <em>top-level sections only</em>. A CCD nests sections, and Horizon's export puts some entries
 * a level down (which is why the CLI index recursed). Relying on the search parameter alone
 * silently left {@code Ecdsaltid_01} blank for every such record, and {@code Ecdsaltid_01} is a
 * required column that the CLI populated on 668 of 668 rows. Walking the Composition in Java is
 * the only way to see a nested entry.
 *
 * <p>Caches: the member index (small — a member's documents are used while that member's records
 * are being walked and then not again), plus per-record and per-encounter answers for the
 * fallback searches. Safe to share across export work chunks.
 *
 * <p>Remaining difference from the CLI index, to confirm by diffing against it on real data: the
 * index's "outward" trace — a resource listed in a document pulls in whatever it references — is
 * not reproduced, since in the POC export it only ever reached non-row types. If a diff shows
 * rows losing {@code Ecdsaltid_01}, that trace is what to add here.
 */
public final class DaoDocumentResolver implements DocumentResolver {

    private static final Logger ourLog = LoggerFactory.getLogger(DaoDocumentResolver.class);

    /**
     * Member indexes held at once. A chunk walks one member at a time, so only the members the
     * batch worker threads have in flight are live; this is sized well past that rather than in
     * proportion to the resource caches, because one entry here is a whole member's documents.
     */
    private static final int MIN_MEMBER_CACHE = 64;
    private static final int MEMBER_CACHE_FRACTION = 100;

    private final DaoRegistry myDaoRegistry;
    private final Supplier<RequestDetails> myRequestDetails;
    private final Cache<String, Optional<SourceDocument>> myByRecord;
    private final Cache<String, Optional<SourceDocument>> myByEncounter;
    private final Cache<String, MemberDocuments> myByMember;
    private final LongAdder mySearches = new LongAdder();

    public DaoDocumentResolver(DaoRegistry theDaoRegistry, Supplier<RequestDetails> theRequestDetails,
                               long theCacheSize) {
        myDaoRegistry = theDaoRegistry;
        myRequestDetails = theRequestDetails;
        // expireAfterWrite on all three: this resolver is held for the life of the Smile
        // process, so without a TTL a document answer — including a cached "no document",
        // which blanks the required Ecdsaltid_01 — would be reused by every later export.
        // See EcdsConverterConfig.DEFAULT_CACHE_TTL.
        myByRecord = Caffeine.newBuilder().maximumSize(theCacheSize)
                .expireAfterWrite(EcdsConverterConfig.DEFAULT_CACHE_TTL).build();
        myByEncounter = Caffeine.newBuilder().maximumSize(theCacheSize)
                .expireAfterWrite(EcdsConverterConfig.DEFAULT_CACHE_TTL).build();
        myByMember = Caffeine.newBuilder()
                .maximumSize(Math.max(MIN_MEMBER_CACHE, theCacheSize / MEMBER_CACHE_FRACTION))
                .expireAfterWrite(EcdsConverterConfig.DEFAULT_CACHE_TTL).build();
    }

    @Override
    public Optional<SourceDocument> documentFor(JsonNode theRecord) {
        if (theRecord == null) {
            return Optional.empty();
        }
        String type = theRecord.path("resourceType").asText();
        Optional<String> id = FhirPaths.primitive(theRecord, "id");
        if (type.isEmpty() || id.isEmpty()) {
            return Optional.empty();
        }
        String reference = type + "/" + id.get();
        return myByRecord.get(reference, ignored -> lookup(type, reference, theRecord));
    }

    /** Storage searches actually performed, for tuning. */
    public long searches() {
        return mySearches.sum();
    }

    private Optional<SourceDocument> lookup(String theType, String theReference, JsonNode theRecord) {
        // The visit this record belongs to; an Encounter row is its own visit.
        Optional<String> encounter = "Encounter".equals(theType)
                ? Optional.of(theReference)
                : FhirPaths.reference(theRecord.get("encounter")).map(DaoDocumentResolver::normalise);

        MemberDocuments member = memberDocuments(theRecord);
        Optional<SourceDocument> fromMember = member.forRecord(normalise(theReference))
                .or(() -> encounter.flatMap(member::forRecord));
        if (fromMember.isPresent()) {
            return fromMember;
        }

        // Not in any of this member's documents: a document whose subject is someone else, or
        // none at all. Fall back to the reverse searches.
        Map<String, SourceDocument> direct = compositionsWhere(Composition.SP_ENTRY, theReference);
        if (!direct.isEmpty()) {
            return single(direct);
        }
        return encounter.flatMap(found -> myByEncounter.get(found, this::viaEncounter));
    }

    /**
     * Every document that names this member as its subject, indexed the way the CLI's index
     * built itself. Empty when the record names no Patient.
     */
    private MemberDocuments memberDocuments(JsonNode theRecord) {
        Optional<String> patient = FhirPaths.reference(theRecord.get("subject"))
                .or(() -> FhirPaths.reference(theRecord.get("patient")))
                .map(DaoDocumentResolver::normalise)
                .filter(reference -> reference.startsWith("Patient/"));
        return patient.map(reference -> myByMember.get(reference, this::indexMember))
                .orElse(MemberDocuments.EMPTY);
    }

    private MemberDocuments indexMember(String thePatientReference) {
        Map<String, String> byReference = new HashMap<>();
        Map<String, String> encounters = new HashMap<>();
        for (IBaseResource resource : compositions(Composition.SP_PATIENT, thePatientReference)) {
            if (!(resource instanceof Composition composition)) {
                continue;
            }
            String identifier = identifierOf(composition);
            if (identifier == null) {
                continue; // a Composition with no identifier has nothing to put in the column
            }
            // First document to claim a resource wins, so a later one does not silently
            // overwrite the provenance already recorded. Same rule as the CLI index.
            indexSections(composition.getSection(), identifier, byReference);
            if (composition.hasEncounter() && composition.getEncounter().hasReference()) {
                String encounter = normalise(composition.getEncounter().getReference());
                byReference.putIfAbsent(encounter, identifier);
                encounters.putIfAbsent(identifier, encounter);
            }
        }
        return new MemberDocuments(byReference, encounters);
    }

    /**
     * Sections nest, and a CCD puts some entries a level down. The {@code Composition-entry}
     * search parameter is {@code Composition.section.entry} and stops at the top level, so the
     * nesting has to be walked here.
     */
    private static void indexSections(List<Composition.SectionComponent> theSections,
                                      String theDocumentId, Map<String, String> theInto) {
        for (Composition.SectionComponent section : theSections) {
            for (Reference entry : section.getEntry()) {
                if (entry.hasReference()) {
                    theInto.putIfAbsent(normalise(entry.getReference()), theDocumentId);
                }
            }
            indexSections(section.getSection(), theDocumentId, theInto);
        }
    }

    /** The document that describes the visit, or that lists the visit in a section. */
    private Optional<SourceDocument> viaEncounter(String theEncounterReference) {
        Map<String, SourceDocument> found = new LinkedHashMap<>();
        found.putAll(compositionsWhere(Composition.SP_ENCOUNTER, theEncounterReference));
        found.putAll(compositionsWhere(Composition.SP_ENTRY, theEncounterReference));
        return single(found);
    }

    /** Exactly one distinct document, or nothing: a guess would misattribute the row. */
    private static Optional<SourceDocument> single(Map<String, SourceDocument> theByIdentifier) {
        return theByIdentifier.size() == 1
                ? Optional.of(theByIdentifier.values().iterator().next())
                : Optional.empty();
    }

    /**
     * Compositions matching one reference search parameter, keyed by document identifier.
     * A Composition without an identifier is ignored, as the index ignores it.
     */
    private Map<String, SourceDocument> compositionsWhere(String theSearchParam, String theReference) {
        Map<String, SourceDocument> found = new LinkedHashMap<>();
        for (IBaseResource resource : compositions(theSearchParam, theReference)) {
            if (!(resource instanceof Composition composition)) {
                continue;
            }
            String identifier = identifierOf(composition);
            if (identifier == null) {
                continue;
            }
            Optional<String> encounter = composition.hasEncounter() && composition.getEncounter().hasReference()
                    ? Optional.of(normalise(composition.getEncounter().getReference()))
                    : Optional.empty();
            found.putIfAbsent(identifier, new SourceDocument(identifier, encounter));
        }
        return found;
    }

    /**
     * One Composition search, ordered so repeated runs over unchanged data resolve the same way.
     *
     * <p>A failure here blanks a column; it must not fail the export job. An instance that does
     * not serve Composition, or that has the {@code entry} / {@code encounter} / {@code patient}
     * search parameters disabled, is reported once per occurrence and treated as "no document".
     */
    private List<IBaseResource> compositions(String theSearchParam, String theReference) {
        mySearches.increment();
        SearchParameterMap params = new SearchParameterMap();
        params.setLoadSynchronous(true);
        params.setSort(new SortSpec(Constants.PARAM_LASTUPDATED).setChain(new SortSpec(Constants.PARAM_ID)));
        params.add(theSearchParam, new ReferenceParam(theReference));
        try {
            IFhirResourceDao<Composition> dao = myDaoRegistry.getResourceDao(Composition.class);
            IBundleProvider result = dao.search(params, myRequestDetails.get());
            return result.getAllResources();
        } catch (BaseServerResponseException | IllegalArgumentException e) {
            ourLog.warn("ECDS: Composition?{}={} failed ({}); Ecdsaltid_01 will be blank for the"
                    + " records it would have covered", theSearchParam, theReference, e.toString());
            return List.of();
        }
    }

    private static String identifierOf(Composition theComposition) {
        if (!theComposition.hasIdentifier()) {
            return null;
        }
        String value = theComposition.getIdentifier().getValue();
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * {@code "http://host/fhir/Condition/1/_history/2"} and {@code "Condition/1"} name the same
     * record, and a CCD may write either form in a section entry. Anything that is not a
     * {@code Type/id} reference — a {@code urn:uuid:} for one — is kept verbatim.
     */
    private static String normalise(String theReference) {
        if (theReference == null || theReference.isBlank()) {
            return "";
        }
        IdType id = new IdType(theReference.trim());
        return id.hasResourceType() && id.hasIdPart()
                ? id.toUnqualifiedVersionless().getValue()
                : theReference.trim();
    }

    /** One member's documents: which document carried each record, and each document's visit. */
    private static final class MemberDocuments {

        private static final MemberDocuments EMPTY = new MemberDocuments(Map.of(), Map.of());

        private final Map<String, String> myDocumentIdByReference;
        private final Map<String, String> myEncounterByDocumentId;

        private MemberDocuments(Map<String, String> theDocumentIdByReference,
                                Map<String, String> theEncounterByDocumentId) {
            myDocumentIdByReference = theDocumentIdByReference;
            myEncounterByDocumentId = theEncounterByDocumentId;
        }

        Optional<SourceDocument> forRecord(String theReference) {
            String documentId = myDocumentIdByReference.get(theReference);
            return documentId == null
                    ? Optional.empty()
                    : Optional.of(new SourceDocument(documentId,
                            Optional.ofNullable(myEncounterByDocumentId.get(documentId))));
        }
    }
}
