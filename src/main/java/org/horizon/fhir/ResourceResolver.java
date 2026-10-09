package org.horizon.fhir;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * Resolves a FHIR reference to the resource it points at.
 *
 * <p>This is the seam between the column logic and wherever the data lives. The CLI implements it
 * with an in-memory index built from a whole export ({@link ReferenceIndex}); the interceptor
 * implements it with reads against the CDR's own storage, behind a bounded cache. The column
 * classes depend only on this interface, so they do not change between the two.
 *
 * <p>Contract shared by every implementation:
 * <ul>
 *   <li>A local {@code "#id"} reference is resolved against {@code theContainingResource}'s
 *       {@code contained} array and nowhere else — contained ids are unique only within their
 *       parent.</li>
 *   <li>A reference that cannot be resolved yields {@link Optional#empty()}; nothing throws and
 *       nothing returns null. A missing lookup target blanks a column and is audited; it must
 *       never abort a run.</li>
 * </ul>
 */
public interface ResourceResolver {

    /**
     * @param theReference          the literal reference, e.g. {@code "Patient/2518"} or
     *                              {@code "#med-1"}
     * @param theContainingResource the resource the reference was found on, consulted for
     *                              contained resources; may be null
     */
    Optional<JsonNode> resolve(String theReference, JsonNode theContainingResource);

    /** Resolves an absolute {@code "Type/id"} reference. */
    default Optional<JsonNode> resolve(String theReference) {
        return resolve(theReference, null);
    }

    /** The shared contained-resource lookup, so no implementation re-derives it. */
    static Optional<JsonNode> contained(JsonNode theContainingResource, String theLocalId) {
        if (theContainingResource == null || theLocalId == null) {
            return Optional.empty();
        }
        return FhirPaths.array(theContainingResource, "contained")
                .filter(contained -> FhirPaths.primitive(contained, "id")
                        .filter(theLocalId::equals)
                        .isPresent())
                .findFirst();
    }
}
