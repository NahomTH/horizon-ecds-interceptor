package org.horizon.fhir;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Works out which source document each clinical resource came from, for {@code Ecdsaltid_01}.
 *
 * <p>A CCD arrives as a Composition whose sections list the resources it contained, which
 * covers most rows directly. The rest are reached transitively: a Condition may be absent from
 * every section yet referenced by an Encounter that is listed, so it belongs to that
 * Encounter's document.
 *
 * <p>The value is the document identifier alone — {@code Composition.identifier.value}, which
 * is the CCD's {@code ClinicalDocument/id} carried over from the source document. Horizon asked
 * for the identifier on its own, with no resource type or search syntax around it.
 *
 * <p>Usage: feed every resource to {@link #add} and {@link #addReferrer} during a first pass,
 * then call {@link #linkReferrers} once before any lookup. Linking consumes and releases the
 * referrer map, so it is a one-way step — calling {@link #addReferrer} afterwards would record
 * references that no later linking pass will act on.
 */
public class CompositionIndex implements DocumentResolver {

    /** {@code "Condition/2678"} to the identifier of the document that carried it. */
    private final Map<String, String> myDocumentIds = new HashMap<>();

    /** Every resource a given resource mentions, built so the trace can run outward. */
    private final Map<String, Set<String>> myReferencesMade = new HashMap<>();

    /** Document identifier to the encounter that document describes, where it names one. */
    private final Map<String, String> myDocumentEncounters = new HashMap<>();

    /** Indexes a Composition's section entries; anything else is ignored here. */
    public void add(JsonNode theResource) {
        if (theResource == null || !"Composition".equals(theResource.path("resourceType").asText())) {
            return;
        }
        Optional<String> identifier = FhirPaths.primitive(theResource.get("identifier"), "value");
        if (identifier.isEmpty()) {
            return;
        }
        String documentId = identifier.get();
        indexSections(theResource, documentId);
        // The visit the document describes belongs to it as surely as its section entries do,
        // and it is the route by which resources hanging off that visit are reached.
        FhirPaths.reference(theResource.get("encounter")).ifPresent(encounter -> {
            myDocumentIds.putIfAbsent(encounter, documentId);
            myDocumentEncounters.putIfAbsent(documentId, encounter);
        });
    }

    /** Records what this resource references, so resources it mentions can be traced to it. */
    public void addReferrer(JsonNode theResource) {
        if (theResource == null) {
            return;
        }
        Optional<String> id = FhirPaths.primitive(theResource, "id");
        String type = theResource.path("resourceType").asText();
        if (id.isEmpty() || type.isEmpty()) {
            return;
        }
        Set<String> targets = new HashSet<>();
        collectReferences(theResource, targets);
        if (!targets.isEmpty()) {
            myReferencesMade.put(type + "/" + id.get(), targets);
        }
    }

    /**
     * Assigns a document to resources that no Composition lists directly, by looking at what
     * does reference them.
     *
     * <p>A resource cited by several documents — a Practitioner or Patient shared across a
     * member's records — is deliberately left unassigned. There is no single source document,
     * and picking one would attribute a row to a document that did not carry it.
     */
    public void linkReferrers() {
        Map<String, Set<String>> candidates = new HashMap<>();
        myReferencesMade.forEach((resource, targets) -> {
            String resourceDocument = myDocumentIds.get(resource);
            for (String target : targets) {
                // Outward: a resource in a document pulls in what it references.
                if (resourceDocument != null && !myDocumentIds.containsKey(target)) {
                    candidates.computeIfAbsent(target, key -> new HashSet<>()).add(resourceDocument);
                }
                // Inward: a resource that references something in a document belongs to it too.
                // Condition/3221 is reached this way, through the Composition's own encounter.
                String targetDocument = myDocumentIds.get(target);
                if (targetDocument != null && !myDocumentIds.containsKey(resource)) {
                    candidates.computeIfAbsent(resource, key -> new HashSet<>()).add(targetDocument);
                }
            }
        });
        candidates.forEach((target, documents) -> {
            if (documents.size() == 1) {
                myDocumentIds.put(target, documents.iterator().next());
            }
        });
        // Nothing reads the referrer map after this point, and it is the largest thing this
        // index holds — around 179 MB on a 5000-CCD export. Releasing it here hands that heap
        // back before the mapper's second pass, which is the part that needs the headroom.
        myReferencesMade.clear();
    }

    /**
     * How many resources still have their outbound references held.
     *
     * <p>Zero after {@link #linkReferrers()}, which is the point: the map exists only to be
     * consumed by linking.
     */
    public int referrerCount() {
        return myReferencesMade.size();
    }

    /** The in-memory answer: direct listing or traced, with the document's encounter alongside. */
    @Override
    public Optional<SourceDocument> documentFor(JsonNode theRecord) {
        if (theRecord == null) {
            return Optional.empty();
        }
        String type = theRecord.path("resourceType").asText();
        return FhirPaths.primitive(theRecord, "id")
                .flatMap(id -> documentIdFor(type + "/" + id))
                .map(documentId -> new SourceDocument(documentId,
                        Optional.ofNullable(myDocumentEncounters.get(documentId))));
    }

    /** The source document for {@code "<Type>/<id>"}, direct or traced. */
    public Optional<String> documentIdFor(String theResourceReference) {
        if (theResourceReference == null || theResourceReference.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(myDocumentIds.get(theResourceReference.trim()));
    }

    /**
     * The encounter described by the document that carried {@code theResourceReference}.
     *
     * <p>Some resources — Procedures in this export — name no encounter of their own, but the
     * document they arrived in describes exactly one visit, and that is where the provider for
     * the event is recorded.
     */
    public Optional<String> encounterOfDocumentFor(String theResourceReference) {
        return documentIdFor(theResourceReference).map(myDocumentEncounters::get);
    }

    public int size() {
        return myDocumentIds.size();
    }

    /** Sections nest, and the export puts some entries a level down, so recurse. */
    private void indexSections(JsonNode theParent, String theDocumentId) {
        FhirPaths.array(theParent, "section").forEach(section -> {
            FhirPaths.array(section, "entry").forEach(entry ->
                    FhirPaths.reference(entry).ifPresent(reference ->
                            // First document to claim a resource wins, so a later one does not
                            // silently overwrite the provenance already recorded.
                            myDocumentIds.putIfAbsent(reference, theDocumentId)));
            indexSections(section, theDocumentId);
        });
    }

    /**
     * Every {@code "Type/id"} reference anywhere in the resource. Contained {@code "#id"}
     * references are skipped: they are local to their parent and say nothing about provenance.
     */
    private static void collectReferences(JsonNode theNode, Set<String> theTargets) {
        if (theNode.isObject()) {
            JsonNode reference = theNode.get("reference");
            if (reference != null && reference.isTextual()) {
                String value = reference.asText();
                if (value.contains("/") && !value.startsWith("#")) {
                    theTargets.add(value);
                }
            }
            theNode.fields().forEachRemaining(field -> collectReferences(field.getValue(), theTargets));
        } else if (theNode.isArray()) {
            theNode.forEach(element -> collectReferences(element, theTargets));
        }
    }
}
