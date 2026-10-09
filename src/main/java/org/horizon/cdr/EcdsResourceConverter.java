package org.horizon.cdr;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.api.server.bulk.BulkExportJobParameters;
import ca.uhn.fhir.rest.api.server.bulk.BulkExportResourceList;
import ca.uhn.fhir.rest.api.server.bulk.ConvertedFile;
import ca.uhn.fhir.rest.api.server.bulk.ConvertedFiles;
import ca.uhn.fhir.rest.api.server.bulk.IResourceConverter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nonnull;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.horizon.ecds.AuditLog;
import org.horizon.ecds.EcdsRow;
import org.horizon.ecds.EcdsRowBuilder;
import org.horizon.ecds.FeedConstants;
import org.horizon.ecds.RequiredColumnsAudit;
import org.horizon.fhir.DocumentResolver;
import org.horizon.fhir.FhirPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * The ECDS output format for bulk export: turns each work chunk into CSV files.
 *
 * <p>This is what the {@code STORAGE_BULK_EXPORT_RESOURCE_CONVERT} hook returns. The export job
 * selects the resources ({@code _type}, {@code _typeFilter}, {@code _since}), fetches them in
 * chunks of one type, and stores whatever this returns as Binary resources listed in the export
 * manifest. One call = one chunk of one type, up to the server's bulk-export file capacity
 * (1,000 resources by default). Chunks run concurrently, so nothing per-call lives in a field.
 *
 * <p><b>Patient-driven</b> (the intended way to run it): export {@code _type=Patient}. Every
 * chunk is a batch of members; for each member this walks the clinical records that reference
 * them ({@link PatientCompartmentSource}), builds rows, and the member's rows land together in
 * one file. Patients, Practitioners, Encounters and Compositions are reached through the
 * resolvers, which are shared across chunks and cached.
 *
 * <p><b>Resource-driven</b> (also supported): export the clinical types themselves and each
 * chunk becomes rows directly. If an export names Patient <em>and</em> clinical types, the
 * clinical chunks yield header-only files so no row is produced twice.
 *
 * <p>Output per call: one or more CSV files labelled with the chunk's resource type (split at
 * {@code maxFileBytes}), plus the chunk's audit entries as a file labelled {@code EcdsAudit}, so
 * the error file reaches Horizon through the same manifest. A chunk with no rows still returns
 * a header-only file: the export step fails the job if a converter returns nothing.
 */
public final class EcdsResourceConverter implements IResourceConverter {

    private static final Logger ourLog = LoggerFactory.getLogger(EcdsResourceConverter.class);
    private static final String PATIENT = "Patient";

    private final FhirContext myContext;
    private final DaoResourceResolver myResolver;
    private final DocumentResolver myDocuments;
    private final PatientCompartmentSource myCompartment;
    private final EcdsConverterConfig myConfig;
    private final ObjectMapper myJson = new ObjectMapper();
    private final LongAdder myChunks = new LongAdder();
    private final LongAdder myRows = new LongAdder();
    private final LongAdder myAuditEntries = new LongAdder();

    public EcdsResourceConverter(FhirContext theContext, DaoResourceResolver theResolver,
                                 DocumentResolver theDocuments, PatientCompartmentSource theCompartment,
                                 EcdsConverterConfig theConfig) {
        myContext = theContext;
        myResolver = theResolver;
        myDocuments = theDocuments;
        myCompartment = theCompartment;
        myConfig = theConfig;
    }

    @Override
    @Nonnull
    public ConvertedFiles consume(@Nonnull BulkExportResourceList theResources,
                                  @Nonnull BulkExportJobParameters theJobParameters) {
        List<IBaseResource> resources = theResources.getResources();
        String resourceType = myContext.getResourceType(resources.get(0));
        String mimeType = theJobParameters.getOutputFormat() == null
                ? myConfig.outputFormat()
                : theJobParameters.getOutputFormat();

        EcdsFileSet data = new EcdsFileSet(resourceType, mimeType, myConfig.maxFileBytes());
        // Bytes, not a StringWriter: on Horizon-shaped data the audit is about as large as the
        // CSV itself (1.7 entries per row), and a StringWriter would hold it as chars and then
        // copy it twice more on the way to the byte[] the file wants.
        ByteArrayOutputStream auditBuffer = new ByteArrayOutputStream();
        long auditEntries;
        long recordsRead = 0;
        try (AuditLog audit = new AuditLog(new OutputStreamWriter(auditBuffer, StandardCharsets.UTF_8))) {
            EcdsRowBuilder builder = new EcdsRowBuilder(
                    FeedConstants.forFeed(myConfig.fallbackFeed()), myResolver, myDocuments, audit);
            IParser parser = myContext.newJsonParser();

            if (PATIENT.equals(resourceType)) {
                // One walk per member, not per resource in the chunk. With _includeHistory the
                // chunk holds every version of each Patient, and walking each of them would
                // export the member's whole record set once per version — every row duplicated
                // as many times as the member has ever been updated.
                Set<String> walked = new HashSet<>();
                for (IBaseResource resource : resources) {
                    JsonNode patient = toJson(parser, resource);
                    Optional<String> id = FhirPaths.primitive(patient, "id");
                    if (id.isEmpty()) {
                        // Nothing to search the compartment by. Cannot happen for a resource
                        // that came out of storage, but "Patient/" would reach the DAO as a
                        // malformed reference and fail the chunk for the wrong reason.
                        ourLog.warn("ECDS export: skipping a Patient with no id in chunk of {}",
                                resources.size());
                        continue;
                    }
                    if (!walked.add(id.get())) {
                        continue; // an earlier version of a member already walked
                    }
                    // The rows for this member will resolve their subject straight from cache.
                    myResolver.prime(patient);
                    recordsRead += myCompartment.forEachRecord(PATIENT + "/" + id.get(),
                            record -> emit(builder, record, data, audit));
                }
            } else if (EcdsRowBuilder.producesRows(resourceType) && !exportsPatients(theJobParameters)) {
                for (IBaseResource resource : resources) {
                    emit(builder, toJson(parser, resource), data, audit);
                    recordsRead++;
                }
            }
            auditEntries = audit.total();
        } catch (IOException e) {
            throw new UncheckedIOException("could not write the ECDS audit for " + resourceType, e);
        }

        ConvertedFiles files = new ConvertedFiles();
        data.addTo(files);
        if (auditEntries > 0 && myConfig.writesAudit()) {
            files.addFile(auditFile(mimeType, auditBuffer.toByteArray()));
        }

        myChunks.increment();
        myRows.add(data.rows());
        myAuditEntries.add(auditEntries);
        ourLog.info("ECDS export: {} {} resource(s), {} record(s) read -> {} row(s) in {} file(s), {} audit entries (job {})",
                resources.size(), resourceType, recordsRead, data.rows(), data.fileCount(), auditEntries,
                theJobParameters.getExportIdentifier());
        return files;
    }

    public long chunksConverted() {
        return myChunks.sum();
    }

    public long rowsWritten() {
        return myRows.sum();
    }

    public long auditEntries() {
        return myAuditEntries.sum();
    }

    private static void emit(EcdsRowBuilder theBuilder, JsonNode theRecord, EcdsFileSet theData, AuditLog theAudit) {
        for (EcdsRow row : theBuilder.build(theRecord)) {
            RequiredColumnsAudit.check(row, theAudit);
            theData.write(row);
        }
    }

    /** True when the export job itself lists Patient, in which case rows come from the fan-out only. */
    private static boolean exportsPatients(BulkExportJobParameters theParameters) {
        List<String> types = theParameters.getResourceTypes();
        return types != null && types.contains(PATIENT);
    }

    private JsonNode toJson(IParser theParser, IBaseResource theResource) {
        try {
            return myJson.readTree(theParser.encodeResourceToString(theResource));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not re-read encoded " + theResource.getIdElement(), e);
        }
    }

    private ConvertedFile auditFile(String theMimeType, byte[] theContent) {
        ConvertedFile file = new ConvertedFile();
        file.setResourceType(myConfig.auditType());
        file.setMimeType(theMimeType);
        file.setBytes(theContent);
        return file;
    }
}
