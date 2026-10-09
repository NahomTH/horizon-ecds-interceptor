package org.horizon.cdr;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.horizon.ecds.EcdsRowBuilder;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Walks one member's clinical records — the agreed patient-driven export.
 *
 * <p>The export is run on {@code Patient} only. For each Patient in a chunk this fetches the
 * row-producing types that reference that patient ({@code Condition?patient=Patient/x},
 * {@code Observation?patient=...}, and so on, in a fixed order) and hands each record to the
 * row builder. Practitioners, Organizations, Encounters and Compositions are then reached
 * through the resolvers as each row needs them.
 *
 * <p>Every type here has the standard R4 {@code patient} search parameter (the patient
 * compartment), so one parameter name serves all of them. Searches are offset-paged so a member
 * with thousands of Observations does not have to fit in one page.
 */
public final class PatientCompartmentSource {

    private static final String PATIENT_PARAM = "patient";

    private final DaoRegistry myDaoRegistry;
    private final FhirContext myContext;
    private final Supplier<RequestDetails> myRequestDetails;
    private final ObjectMapper myJson = new ObjectMapper();
    private final int myPageSize;
    private final LongAdder mySearches = new LongAdder();
    private final LongAdder myRecords = new LongAdder();

    public PatientCompartmentSource(DaoRegistry theDaoRegistry, FhirContext theContext,
                                    Supplier<RequestDetails> theRequestDetails, int thePageSize) {
        myDaoRegistry = theDaoRegistry;
        myContext = theContext;
        myRequestDetails = theRequestDetails;
        myPageSize = thePageSize;
    }

    /**
     * Hands every row-producing record of {@code thePatientReference} ({@code "Patient/id"})
     * to {@code theHandler}, type by type in {@link EcdsRowBuilder#rowProducingTypesInOrder()}.
     *
     * @return how many records were read
     */
    public long forEachRecord(String thePatientReference, Consumer<JsonNode> theHandler) {
        IParser parser = myContext.newJsonParser();
        long read = 0;
        for (String type : EcdsRowBuilder.rowProducingTypesInOrder()) {
            IFhirResourceDao<? extends IBaseResource> dao = myDaoRegistry.getResourceDaoOrNull(type);
            if (dao == null) {
                continue; // a type this server does not serve
            }
            read += readType(dao, type, thePatientReference, parser, theHandler);
        }
        myRecords.add(read);
        return read;
    }

    /**
     * One type's records for one member, a page at a time.
     *
     * <p>A failure here is deliberately <b>not</b> swallowed. Skipping a type would hand Horizon
     * a CSV that is quietly missing every Condition — a short file that looks like a complete
     * one, which in a HEDIS feed under-reports measures. The export job failing is visible;
     * a silently short submission is not. The message names the type and the member so the
     * failed job says what to fix.
     */
    private long readType(IFhirResourceDao<? extends IBaseResource> theDao, String theType,
                          String thePatientReference, IParser theParser, Consumer<JsonNode> theHandler) {
        long read = 0;
        int offset = 0;
        String previousPageStart = null;
        try {
            while (true) {
                mySearches.increment();
                IBundleProvider page = theDao.search(query(thePatientReference, offset), myRequestDetails.get());
                List<IBaseResource> resources = page.getResources(0, myPageSize);
                if (resources.isEmpty()) {
                    break;
                }
                // If the offset were ignored, every page would repeat the first one and this
                // would stream the same records forever. Cheap to rule out, and a silent
                // infinite loop in a batch worker is otherwise very hard to diagnose.
                String pageStart = resources.get(0).getIdElement().toUnqualifiedVersionless().getValue();
                if (offset > 0 && pageStart != null && pageStart.equals(previousPageStart)) {
                    throw new IllegalStateException("paging made no progress at offset " + offset
                            + ": the search is not honouring _offset");
                }
                previousPageStart = pageStart;

                for (IBaseResource resource : resources) {
                    theHandler.accept(toJson(theParser, resource));
                    read++;
                }
                if (resources.size() < myPageSize) {
                    break;
                }
                offset += resources.size();
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("ECDS export could not read " + theType + " for "
                    + thePatientReference + " (offset " + offset + "); the "
                    + PATIENT_PARAM + " search parameter has to be enabled on " + theType, e);
        }
        return read;
    }

    public long searches() {
        return mySearches.sum();
    }

    public long records() {
        return myRecords.sum();
    }

    private SearchParameterMap query(String thePatientReference, int theOffset) {
        SearchParameterMap params = new SearchParameterMap();
        params.setLoadSynchronous(true);
        params.setCount(myPageSize);
        params.setOffset(theOffset);
        // Sorted by id alone. The obvious key, _lastUpdated, is the one thing that changes while
        // the walk is in progress: a record written to between two pages moves to the end of the
        // ordering, every record behind it shifts down one, and the record that slid past the
        // offset is never read. The id never moves, so the pages stay disjoint.
        params.setSort(new SortSpec(Constants.PARAM_ID));
        params.add(PATIENT_PARAM, new ReferenceParam(thePatientReference));
        return params;
    }

    private JsonNode toJson(IParser theParser, IBaseResource theResource) {
        try {
            return myJson.readTree(theParser.encodeResourceToString(theResource));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not re-read encoded " + theResource.getIdElement(), e);
        }
    }
}
