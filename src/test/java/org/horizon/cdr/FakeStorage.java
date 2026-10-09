package org.horizon.cdr;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.model.api.IQueryParameterType;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.SimpleBundleProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A {@link DaoRegistry} that serves resources from memory, for testing the storage-backed
 * classes without a database.
 *
 * <p>Implements just enough of the DAO contract for the ECDS classes: {@code read} by id,
 * {@code search} with offset/count paging, the {@code _tag} filter, and the Composition
 * {@code entry} / {@code encounter} reference searches. Results are ordered by id so paging is
 * deterministic.
 */
final class FakeStorage {

    private final FhirContext myContext;
    // Concurrent throughout: the converter is shared across batch worker threads, so the
    // double behind it has to tolerate the same concurrency the real DaoRegistry does.
    private final Map<String, List<IBaseResource>> myByType = new ConcurrentHashMap<>();
    private final Map<String, IBaseResource> myById = new ConcurrentHashMap<>();
    private final Map<String, IFhirResourceDao<IBaseResource>> myDaos = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.atomic.LongAdder> myReads = new ConcurrentHashMap<>();
    private final DaoRegistry myRegistry = mock(DaoRegistry.class);

    FakeStorage(FhirContext theContext) {
        myContext = theContext;
        when(myRegistry.getResourceDao(anyString())).thenAnswer(invocation -> daoFor(invocation.getArgument(0)));
        when(myRegistry.getResourceDaoOrNull(anyString())).thenAnswer(invocation ->
                myByType.containsKey((String) invocation.getArgument(0)) ? daoFor(invocation.getArgument(0)) : null);
        when(myRegistry.getResourceDao(any(Class.class))).thenAnswer(invocation ->
                daoFor(myContext.getResourceType((Class<? extends IBaseResource>) invocation.getArgument(0))));
    }

    /** Loads every {@code *.ndjson} in a folder, as a bulk export would have produced them. */
    static FakeStorage fromNdjsonFolder(FhirContext theContext, Path theFolder) throws IOException {
        FakeStorage storage = new FakeStorage(theContext);
        try (Stream<Path> files = Files.list(theFolder)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".ndjson")).sorted().toList()) {
                for (String line : Files.readAllLines(file)) {
                    if (!line.isBlank()) {
                        storage.add(theContext.newJsonParser().parseResource(line));
                    }
                }
            }
        }
        return storage;
    }

    FakeStorage add(IBaseResource theResource) {
        String type = myContext.getResourceType(theResource);
        myByType.computeIfAbsent(type, key -> new CopyOnWriteArrayList<>()).add(theResource);
        myById.put(type + "/" + theResource.getIdElement().getIdPart(), theResource);
        return this;
    }

    DaoRegistry registry() {
        return myRegistry;
    }

    /** How many times {@code read} was called for a type, so a test can pin what was cached. */
    long readsOf(String theType) {
        var adder = myReads.get(theType);
        return adder == null ? 0 : adder.sum();
    }

    @SuppressWarnings("unchecked")
    private IFhirResourceDao<IBaseResource> daoFor(String theType) {
        return myDaos.computeIfAbsent(theType, type -> {
            IFhirResourceDao<IBaseResource> dao = mock(IFhirResourceDao.class);
            when(dao.read(any(IIdType.class), any(RequestDetails.class))).thenAnswer(invocation -> {
                IIdType id = invocation.getArgument(0);
                myReads.computeIfAbsent(type, k -> new java.util.concurrent.atomic.LongAdder()).increment();
                IBaseResource found = myById.get(id.toUnqualifiedVersionless().getValue());
                if (found == null) {
                    throw new ResourceNotFoundException(id);
                }
                return found;
            });
            when(dao.search(any(SearchParameterMap.class), any(RequestDetails.class))).thenAnswer(invocation ->
                    new SimpleBundleProvider(search(type, invocation.getArgument(0))));
            return dao;
        });
    }

    private List<IBaseResource> search(String theType, SearchParameterMap theParams) {
        List<IBaseResource> matches = new ArrayList<>(myByType.getOrDefault(theType, List.of()));
        matches.removeIf(resource -> !matchesTag(resource, theParams));
        matches.removeIf(resource -> !matchesReference(resource, theParams, Composition.SP_ENTRY, FakeStorage::sectionEntries));
        matches.removeIf(resource -> !matchesReference(resource, theParams, Composition.SP_ENCOUNTER, FakeStorage::encounterOf));
        matches.removeIf(resource -> !matchesReference(resource, theParams, "patient", this::patientOf));
        matches.sort(Comparator.comparing(resource -> resource.getIdElement().getIdPart()));

        int from = theParams.getOffset() == null ? 0 : theParams.getOffset();
        int to = theParams.getCount() == null ? matches.size() : Math.min(matches.size(), from + theParams.getCount());
        return from >= matches.size() ? List.of() : matches.subList(from, to);
    }

    private static boolean matchesTag(IBaseResource theResource, SearchParameterMap theParams) {
        List<List<IQueryParameterType>> wanted = theParams.get(Constants.PARAM_TAG);
        if (wanted == null || wanted.isEmpty()) {
            return true;
        }
        for (List<IQueryParameterType> orList : wanted) {
            boolean any = orList.stream().anyMatch(param -> {
                TokenParam token = (TokenParam) param;
                return theResource.getMeta().getTag().stream().anyMatch(tag ->
                        token.getSystem().equals(tag.getSystem()) && token.getValue().equals(tag.getCode()));
            });
            if (!any) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesReference(IBaseResource theResource, SearchParameterMap theParams,
                                            String theParam, java.util.function.Function<Resource, List<String>> theReferences) {
        List<List<IQueryParameterType>> wanted = theParams.get(theParam);
        if (wanted == null || wanted.isEmpty()) {
            return true;
        }
        List<String> actual = theReferences.apply((Resource) theResource);
        for (List<IQueryParameterType> orList : wanted) {
            boolean any = orList.stream().anyMatch(param -> actual.contains(((ReferenceParam) param).getValue()));
            if (!any) {
                return false;
            }
        }
        return true;
    }

    /**
     * The R4 {@code Composition-entry} search parameter is defined as
     * {@code Composition.section.entry} — <b>top-level sections only</b>. An entry a level down
     * is not indexed and so is not matched, and this fake has to be just as blind or it would
     * hide exactly the gap {@link DaoDocumentResolver} exists to close.
     */
    private static List<String> sectionEntries(Resource theResource) {
        List<String> references = new ArrayList<>();
        if (theResource instanceof Composition composition) {
            for (Composition.SectionComponent section : composition.getSection()) {
                for (Reference entry : section.getEntry()) {
                    references.add(entry.getReference());
                }
            }
        }
        return references;
    }

    /** The patient compartment: whatever the resource's subject or patient element points at. */
    private List<String> patientOf(Resource theResource) {
        List<String> references = new ArrayList<>();
        for (String element : List.of("subject", "patient")) {
            if (myContext.getResourceDefinition(theResource).getChildByName(element) == null) {
                continue; // this type has no such element
            }
            for (org.hl7.fhir.instance.model.api.IBase value : myContext.newTerser().getValues(theResource, theResource.fhirType() + "." + element)) {
                if (value instanceof Reference reference && reference.hasReference()) {
                    references.add(reference.getReference());
                }
            }
        }
        return references;
    }

    private static List<String> encounterOf(Resource theResource) {
        if (theResource instanceof Composition composition && composition.hasEncounter()) {
            return List.of(composition.getEncounter().getReference());
        }
        return List.of();
    }
}
