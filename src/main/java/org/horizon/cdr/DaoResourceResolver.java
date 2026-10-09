package org.horizon.cdr;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.IdType;
import org.horizon.fhir.ResourceResolver;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Resolves references by reading the CDR's own storage, one resource at a time, behind a cache.
 *
 * <p>This is what replaces the CLI's {@code ReferenceIndex}. The CLI could afford to hold every
 * Patient, Practitioner and Encounter in memory because it owned the whole JVM; inside the CDR it
 * cannot, so lookups are lazy and the cache is bounded. The same handful of Patients and
 * Encounters are referenced by hundreds of records each, so the hit rate is high.
 *
 * <p>Resources come back as HAPI model objects and are re-encoded to JSON so the column logic —
 * written against {@link JsonNode}, and tested that way — is untouched. The encoder is the same
 * one {@code $export} uses, so the shape is identical to what the CLI reads.
 *
 * <p>Safe to share across export work chunks, which run on several threads: the cache is
 * concurrent, counters are adders, and a parser is created per call because {@link IParser}
 * is not thread-safe.
 */
public final class DaoResourceResolver implements ResourceResolver {

    /** How many distinct unresolved references are kept for reporting; the count is unbounded. */
    private static final int UNRESOLVED_SAMPLE_SIZE = 10_000;

    /**
     * The heap budget is {@code cacheSize} times this, so the default 50,000 buys ~100 MB rather
     * than 50,000 whole resources. A Synthea Patient encodes to roughly 2 KB.
     */
    private static final int AVERAGE_RESOURCE_BYTES = 2_048;

    /** Jackson's object tree runs several times the length of the JSON it was parsed from. */
    private static final int JSON_TO_HEAP_FACTOR = 4;

    /** Map slot, key, Optional wrapper: what an entry costs before its value. */
    private static final int EMPTY_ENTRY_BYTES = 128;

    private final DaoRegistry myDaoRegistry;
    private final Supplier<RequestDetails> myRequestDetails;
    private final FhirContext myContext;
    private final ObjectMapper myJson = new ObjectMapper();
    private final Cache<String, Optional<JsonNode>> myCache;
    private final Set<String> myUnresolved = ConcurrentHashMap.newKeySet();
    private final LongAdder myUnresolvedCount = new LongAdder();
    private final LongAdder myReads = new LongAdder();

    /**
     * @param theRequestDetails supplies the request context each read runs under — a
     *                          {@code SystemRequestDetails}, scoped to all partitions if the
     *                          server is partitioned
     * @param theCacheSize      maximum resources held; evicted least-recently-used
     */
    public DaoResourceResolver(DaoRegistry theDaoRegistry, FhirContext theContext,
                               Supplier<RequestDetails> theRequestDetails, long theCacheSize) {
        myDaoRegistry = theDaoRegistry;
        myRequestDetails = theRequestDetails;
        myContext = theContext;
        // Weighed by encoded size, not counted: the values are whole resource trees, and a
        // Patient with fifty extensions is not one entry's worth of heap. maximumSize(50_000)
        // on JsonNode trees is how you pin a gigabyte by accident.
        //
        // expireAfterWrite because this resolver outlives the job that filled it: the converter
        // is held for the life of the process, so without a TTL tonight's export answers from
        // copies read during last night's, and a reference that resolved to nothing then stays
        // unresolved now. See EcdsConverterConfig.DEFAULT_CACHE_TTL.
        myCache = Caffeine.newBuilder()
                .maximumWeight(theCacheSize * AVERAGE_RESOURCE_BYTES)
                .weigher((String key, Optional<JsonNode> value) -> weigh(value))
                .expireAfterWrite(EcdsConverterConfig.DEFAULT_CACHE_TTL)
                .build();
    }

    /**
     * What one entry costs, in bytes, near enough for a budget. A negative entry still occupies
     * a map slot, so it is never free.
     */
    private static int weigh(Optional<JsonNode> theValue) {
        if (theValue == null || theValue.isEmpty()) {
            return EMPTY_ENTRY_BYTES;
        }
        // Rough but monotone in the thing that matters: a JsonNode tree runs several times the
        // length of its JSON, and estimating from the tree's own size avoids re-serialising it.
        return EMPTY_ENTRY_BYTES + theValue.get().toString().length() * JSON_TO_HEAP_FACTOR;
    }

    @Override
    public Optional<JsonNode> resolve(String theReference, JsonNode theContainingResource) {
        if (theReference == null || theReference.isBlank()) {
            return Optional.empty();
        }
        String reference = theReference.trim();
        if (reference.startsWith("#")) {
            return ResourceResolver.contained(theContainingResource, reference.substring(1));
        }
        IdType id = new IdType(reference);
        if (!id.hasResourceType() || !id.hasIdPart()) {
            recordUnresolved(reference);
            return Optional.empty();
        }
        // Keyed without the server base so "http://host/fhir/Patient/1" and "Patient/1" share an
        // entry; a version suffix is kept, since a versioned reference means that version.
        String key = id.toUnqualified().getValue();
        return myCache.get(key, ignored -> read(id.toUnqualified()));
    }

    /**
     * Seeds the cache with a resource the caller already holds — the Patients in an export
     * chunk, so the rows built for them never read the Patient back from storage.
     */
    public void prime(JsonNode theResource) {
        if (theResource == null) {
            return;
        }
        String type = theResource.path("resourceType").asText();
        Optional<String> id = org.horizon.fhir.FhirPaths.primitive(theResource, "id");
        if (!type.isEmpty() && id.isPresent()) {
            myCache.put(type + "/" + id.get(), Optional.of(theResource));
        }
    }

    /** A sample of references that pointed at nothing (first {@value #UNRESOLVED_SAMPLE_SIZE}). */
    public Set<String> unresolvedReferences() {
        return Collections.unmodifiableSet(myUnresolved);
    }

    /** Every unresolved lookup, counted even once the sample is full. */
    public long unresolvedCount() {
        return myUnresolvedCount.sum();
    }

    /** Storage reads actually performed — the cache misses — for tuning the cache size. */
    public long reads() {
        return myReads.sum();
    }

    private void recordUnresolved(String theReference) {
        myUnresolvedCount.increment();
        if (myUnresolved.size() < UNRESOLVED_SAMPLE_SIZE) {
            myUnresolved.add(theReference);
        }
    }

    private Optional<JsonNode> read(IdType theId) {
        myReads.increment();
        try {
            IFhirResourceDao<? extends IBaseResource> dao =
                    myDaoRegistry.getResourceDao(theId.getResourceType());
            IBaseResource resource = dao.read(theId, myRequestDetails.get());
            return Optional.of(toJson(resource));
        } catch (ResourceNotFoundException | ResourceGoneException e) {
            // Missing or deleted: the column goes blank and the row is audited by the caller,
            // exactly as an export that lacked the resource would have been handled.
            recordUnresolved(theId.getValue());
            return Optional.empty();
        } catch (InvalidRequestException e) {
            // A resource type this server does not know. Same outcome: nothing to resolve to.
            recordUnresolved(theId.getValue());
            return Optional.empty();
        }
    }

    private JsonNode toJson(IBaseResource theResource) {
        try {
            IParser parser = myContext.newJsonParser();
            return myJson.readTree(parser.encodeResourceToString(theResource));
        } catch (JsonProcessingException e) {
            // The encoder produced it; this cannot happen without a HAPI bug.
            throw new IllegalStateException("could not re-read encoded " + theResource.getIdElement(), e);
        }
    }
}
