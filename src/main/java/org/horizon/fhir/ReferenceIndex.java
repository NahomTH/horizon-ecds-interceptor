package org.horizon.fhir;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves FHIR references to the resources they point at.
 *
 * <p>Built in a first pass over the export so the second pass can stream clinical records and
 * look up the Patient, Practitioner or Encounter each one references.
 *
 * <p>Only referenceable types are kept. The bulk of an export is Observations and Conditions,
 * which are streamed and never looked up, so indexing them would cost memory for nothing.
 */
public class ReferenceIndex implements ResourceResolver {

    /** Types that ECDS columns resolve <em>to</em>. Everything else is streamed past. */
    private static final Set<String> REFERENCEABLE_TYPES = Set.of(
            "Patient",
            "Practitioner",
            "Encounter",
            "Location",
            "Organization",
            "PractitionerRole",
            "Medication");

    private final Map<String, JsonNode> myResources = new HashMap<>();
    private final Set<String> myUnresolved = new LinkedHashSet<>();

    /** Indexes the resource if it is a type anything refers to; otherwise ignores it. */
    public void add(JsonNode theResource) {
        String type = theResource.path("resourceType").asText();
        if (!REFERENCEABLE_TYPES.contains(type)) {
            return;
        }
        Optional<String> id = FhirPaths.primitive(theResource, "id");
        id.ifPresent(value -> myResources.put(type + "/" + value, theResource));
    }

    /** Resolves an absolute {@code "Type/id"} reference. */
    @Override
    public Optional<JsonNode> resolve(String theReference) {
        return resolve(theReference, null);
    }

    /**
     * Resolves a reference, checking {@code theContainingResource}'s contained resources first
     * when the reference is a local {@code "#id"}.
     *
     * <p>Contained ids are unique only within their parent, so they are deliberately never
     * matched against the global index.
     */
    @Override
    public Optional<JsonNode> resolve(String theReference, JsonNode theContainingResource) {
        if (theReference == null || theReference.isBlank()) {
            return Optional.empty();
        }
        String reference = theReference.trim();

        if (reference.startsWith("#")) {
            return ResourceResolver.contained(theContainingResource, reference.substring(1));
        }

        JsonNode found = myResources.get(reference);
        if (found == null) {
            myUnresolved.add(reference);
            return Optional.empty();
        }
        return Optional.of(found);
    }

    /** References that could not be resolved, for the audit file. */
    public Set<String> unresolvedReferences() {
        return Set.copyOf(myUnresolved);
    }

    public int size() {
        return myResources.size();
    }
}
