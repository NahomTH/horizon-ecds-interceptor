package org.horizon.fhir;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Null-safe navigation over FHIR JSON, aware of {@code data-absent-reason}.
 *
 * <p>FHIR lets an element be present while carrying no value: instead of a code it holds a
 * {@code data-absent-reason} extension meaning "genuinely unknown". The export this tool reads
 * uses that heavily, and on exactly the elements the ECDS columns map from, so every accessor
 * here treats such an element as absent.
 *
 * <p>The pattern appears at three depths, all handled:
 * <ul>
 *   <li>on a {@code Coding} — the coding has a {@code system} but no {@code code}</li>
 *   <li>on a {@code CodeableConcept} — no {@code coding} array at all</li>
 *   <li>on a primitive, via the FHIR {@code _field} convention — {@code _issued} instead of
 *       {@code issued}</li>
 * </ul>
 *
 * <p>Nothing here throws or returns null; callers get an empty {@link Optional} or an empty
 * collection.
 */
public final class FhirPaths {

    private static final String DATA_ABSENT_REASON_URL =
            "http://hl7.org/fhir/StructureDefinition/data-absent-reason";

    private FhirPaths() {
    }

    /** True when the node is missing, JSON null, or carries a {@code data-absent-reason} extension. */
    public static boolean isAbsent(JsonNode theNode) {
        if (theNode == null || theNode.isNull() || theNode.isMissingNode()) {
            return true;
        }
        return hasDataAbsentReason(theNode);
    }

    /**
     * The value of a primitive field, honouring the FHIR {@code _field} convention: empty when the
     * field is missing, blank, JSON null, or present only as {@code _field} carrying a
     * {@code data-absent-reason}.
     */
    public static Optional<String> primitive(JsonNode theParent, String theFieldName) {
        if (theParent == null) {
            return Optional.empty();
        }
        JsonNode value = theParent.get(theFieldName);
        // An explicit JSON null would otherwise stringify to "null".
        if (value == null || value.isNull() || !value.isValueNode()) {
            return Optional.empty();
        }
        String text = value.asText();
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    /**
     * Every code under a {@code CodeableConcept} whose coding matches {@code theSystem}, in source
     * order. Codings that are data-absent, or that carry no {@code system}, never match.
     *
     * <p>Returning a list rather than a single value is deliberate: a Procedure carrying several
     * CPT codes becomes several ECDS rows, and this is what drives that.
     */
    public static List<String> codes(JsonNode theCodeableConcept, String theSystem) {
        List<String> found = new ArrayList<>();
        if (theCodeableConcept == null || theCodeableConcept.isNull() || theCodeableConcept.isMissingNode()) {
            return found;
        }
        // A concept can be marked data-absent and still carry a real coding; the populated
        // coding contradicts the marker, so trust it rather than dropping the code.
        for (JsonNode coding : arrayOf(theCodeableConcept, "coding")) {
            if (isAbsent(coding)) {
                continue;
            }
            Optional<String> system = primitive(coding, "system");
            if (system.isEmpty() || !system.get().equals(theSystem)) {
                continue;
            }
            primitive(coding, "code").ifPresent(found::add);
        }
        return found;
    }

    /** The first code for {@code theSystem}, or empty. */
    public static Optional<String> firstCode(JsonNode theCodeableConcept, String theSystem) {
        List<String> found = codes(theCodeableConcept, theSystem);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** The value of the extension with {@code theUrl}, read from valueCode, valueString or valueCoding.code. */
    public static Optional<String> extensionValue(JsonNode theNode, String theUrl) {
        if (theNode == null) {
            return Optional.empty();
        }
        for (JsonNode extension : arrayOf(theNode, "extension")) {
            if (!primitive(extension, "url").filter(theUrl::equals).isPresent()) {
                continue;
            }
            Optional<String> value = primitive(extension, "valueCode")
                    .or(() -> primitive(extension, "valueString"));
            if (value.isPresent()) {
                return value;
            }
            JsonNode coding = extension.get("valueCoding");
            if (coding != null) {
                return primitive(coding, "code");
            }
        }
        return Optional.empty();
    }

    /** The literal reference, e.g. {@code "Patient/2518"} or {@code "#contained-id"}. */
    public static Optional<String> reference(JsonNode theReferenceNode) {
        return primitive(theReferenceNode, "reference");
    }

    /** The elements of an array field; empty for a missing field or a non-array value. */
    public static Stream<JsonNode> array(JsonNode theParent, String theFieldName) {
        return StreamSupport.stream(arrayOf(theParent, theFieldName).spliterator(), false);
    }

    private static Iterable<JsonNode> arrayOf(JsonNode theParent, String theFieldName) {
        if (theParent == null) {
            return List.of();
        }
        JsonNode value = theParent.get(theFieldName);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        return () -> Spliterators.iterator(
                Spliterators.spliterator(value.elements(), value.size(), Spliterator.ORDERED));
    }

    /**
     * Whether this node itself carries the data-absent-reason <em>extension</em>. Deliberately
     * distinct from {@code Observation.dataAbsentReason}, a real element meaning "no value was
     * obtained", which says nothing about whether the Observation is usable.
     */
    private static boolean hasDataAbsentReason(JsonNode theNode) {
        for (JsonNode extension : arrayOf(theNode, "extension")) {
            if (primitive(extension, "url").filter(DATA_ABSENT_REASON_URL::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
