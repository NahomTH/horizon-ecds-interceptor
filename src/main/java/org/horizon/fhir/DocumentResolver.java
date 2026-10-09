package org.horizon.fhir;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * Works out which source clinical document (CCD) a record came from.
 *
 * <p>Feeds two columns: {@code Ecdsaltid_01} carries the document's identifier, and the
 * provider walk's third step asks the document's encounter for a participant when the record
 * names no performer and no encounter of its own.
 *
 * <p>The CLI answers from {@link CompositionIndex}, built over every Composition in the export.
 * The interceptor answers with reverse lookups against storage
 * ({@code Composition?entry=Type/id}, then {@code Composition?encounter=...}).
 *
 * <p>Contract: a record that several documents could claim yields {@link Optional#empty()}.
 * Attributing a row to a document that did not carry it is worse than leaving the column blank.
 */
public interface DocumentResolver {

    /**
     * @param theRecord the full record, not just its reference — the fallback routes need its
     *                  {@code encounter}
     */
    Optional<SourceDocument> documentFor(JsonNode theRecord);

    /**
     * A source document as the mapping needs it.
     *
     * @param identifier the bare {@code Composition.identifier.value}, emitted verbatim
     * @param encounter  the {@code "Encounter/id"} the document describes, where it names one
     */
    record SourceDocument(String identifier, Optional<String> encounter) {
    }
}
