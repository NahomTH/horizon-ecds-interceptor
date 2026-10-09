package org.horizon.cdr;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.api.server.bulk.BulkExportJobParameters;
import ca.uhn.fhir.rest.api.server.bulk.IResourceConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Registers ECDS as a bulk-export output format.
 *
 * <p>Smile CDR's {@code STORAGE_BULK_EXPORT_RESOURCE_CONVERT} pointcut (HAPI FHIR 8.14 / Smile CDR
 * 2026.11) asks registered hooks for a converter whenever a {@code $export} is validated and
 * whenever a chunk is written. Returning a converter for a given {@code _outputFormat} makes
 * that format valid; returning null leaves the server's own behaviour in place, so NDJSON
 * exports are untouched.
 *
 * <p>So the whole trigger is:
 * <pre>
 *   GET {base}/$export?_outputFormat=text/csv&amp;_type=Patient&amp;_since=1970-01-01T00:00:00Z
 *       [&amp;_typeFilter=Patient?_tag=KonzaHealth]
 *   Prefer: respond-async
 * </pre>
 * then poll the status URL and download the Binaries the manifest lists — {@code tools/run_ecds_export.py}
 * does that. Whatever ships the files to Horizon runs after that step; nothing here knows about S3.
 *
 * <p>Registered on the FHIR Storage module (the export job runs there), on every node that
 * processes batch jobs. Needs {@link DaoRegistry}, which that module provides.
 */
@Interceptor
public class EcdsBulkExportInterceptor {

    private static final Logger ourLog = LoggerFactory.getLogger(EcdsBulkExportInterceptor.class);

    private static final String PATIENT = "Patient";

    /** A ceiling so a pathological caller cannot grow the "already warned" set without bound. */
    private static final int MAX_WARNED_TYPE_LISTS = 64;

    @Autowired
    private DaoRegistry myDaoRegistry;

    @Autowired
    private FhirContext myFhirContext;

    private final EcdsConverterConfig myConfig;
    private final Set<String> myWarnedTypeLists = ConcurrentHashMap.newKeySet();
    private volatile EcdsResourceConverter myConverter;

    /** The constructor Smile uses; settings come from the environment. */
    public EcdsBulkExportInterceptor() {
        this(EcdsConverterConfig.fromEnvironment());
    }

    public EcdsBulkExportInterceptor(EcdsConverterConfig theConfig) {
        myConfig = theConfig;
    }

    /** For tests and for wiring outside Spring. */
    public EcdsBulkExportInterceptor(DaoRegistry theDaoRegistry, FhirContext theFhirContext,
                                     EcdsConverterConfig theConfig) {
        this(theConfig);
        myDaoRegistry = theDaoRegistry;
        myFhirContext = theFhirContext;
    }

    /**
     * @return the ECDS converter when the export asked for the ECDS format, otherwise null so
     *         the server keeps its default handling of the requested format
     */
    @Hook(Pointcut.STORAGE_BULK_EXPORT_RESOURCE_CONVERT)
    public IResourceConverter converterFor(BulkExportJobParameters theParameters) {
        if (theParameters == null || !myConfig.matches(theParameters.getOutputFormat())) {
            return null;
        }
        warnOnceAboutExtraTypes(theParameters);
        return converter();
    }

    /**
     * An ECDS export wants {@code _type=Patient} and nothing else.
     *
     * <p>The reference NDJSON {@code $export} lists sixteen types, and reaching for that command
     * with {@code _outputFormat=text/csv} still works — rows come from the Patient fan-out and the
     * other types are skipped so nothing is counted twice — but the server fetches and expands
     * every Condition, Observation and Procedure on the way to throwing them away, and each of
     * those chunks still has to return a file, so the manifest fills up with header-only CSVs.
     * Worth one line in the log rather than a silently expensive run. The hook is called once
     * per chunk as well as at submission, so repeats are suppressed.
     */
    private void warnOnceAboutExtraTypes(BulkExportJobParameters theParameters) {
        List<String> types = theParameters.getResourceTypes();
        if (types == null || types.size() < 2 || !types.contains(PATIENT)) {
            return;
        }
        if (myWarnedTypeLists.size() < MAX_WARNED_TYPE_LISTS && myWarnedTypeLists.add(String.join(",", types))) {
            ourLog.warn("ECDS export asked for _type={}. Only Patient is needed: every other type"
                            + " is fetched and then skipped, and each of its chunks still adds a"
                            + " header-only CSV to the manifest. Use _type=Patient.",
                    String.join(",", types));
        }
    }

    /** One shared converter: its resolver caches are the point of sharing it across chunks. */
    public EcdsResourceConverter converter() {
        EcdsResourceConverter converter = myConverter;
        if (converter == null) {
            synchronized (this) {
                if (myConverter == null) {
                    // Partition-aware so a partitioned server resolves across all partitions;
                    // a non-partitioned server ignores it.
                    Supplier<RequestDetails> request = SystemRequestDetails::forAllPartitions;
                    myConverter = new EcdsResourceConverter(
                            myFhirContext,
                            new DaoResourceResolver(myDaoRegistry, myFhirContext, request, myConfig.cacheSize()),
                            new DaoDocumentResolver(myDaoRegistry, request, myConfig.cacheSize()),
                            new PatientCompartmentSource(myDaoRegistry, myFhirContext, request, myConfig.pageSize()),
                            myConfig);
                    ourLog.info("ECDS bulk-export converter ready for _outputFormat={} (fallback feed {})",
                            myConfig.outputFormat(), myConfig.fallbackFeed());
                }
                converter = myConverter;
            }
        }
        return converter;
    }
}
