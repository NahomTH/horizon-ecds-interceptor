package org.horizon.cdr;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.api.server.bulk.BulkExportJobParameters;
import ca.uhn.fhir.rest.api.server.bulk.BulkExportResourceList;
import ca.uhn.fhir.rest.api.server.bulk.ConvertedFile;
import ca.uhn.fhir.rest.api.server.bulk.ConvertedFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.horizon.ecds.EcdsColumn;
import org.horizon.ecds.EcdsRow;
import org.horizon.ecds.EcdsRowBuilder;
import org.horizon.ecds.FeedConstants;
import org.horizon.fhir.CompositionIndex;
import org.horizon.fhir.ReferenceIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The acceptance test for the interceptor: the patient-driven export over storage must yield
 * exactly the rows the POC's in-memory mapping yields over the same records. Order may differ,
 * so rows are compared sorted.
 */
class EcdsResourceConverterTest {

    private static final Path MOCK_EXPORT = Path.of("src/test/resources/mock-export");

    /**
     * The mock export has no Composition, so one is added, listing some records directly and
     * naming the encounter the rest hang off — both document routes.
     *
     * <p>Held as a file rather than a string literal because {@code tools/seed_local.py} seeds
     * the same fixture into a live server. Two copies would drift, and the drift would be
     * invisible: the local run would simply disagree with this test for no stated reason.
     */
    private static final Path COMPOSITION_FILE = Path.of("src/test/resources/mock-export-composition.json");

    private final FhirContext myContext = FhirContext.forR4Cached();
    private final Supplier<RequestDetails> myRequest = SystemRequestDetails::new;
    private final ObjectMapper myJson = new ObjectMapper();
    private final List<JsonNode> myRecords = new ArrayList<>();
    private FakeStorage myStorage;

    @BeforeEach
    void loadFixtures() throws IOException {
        myStorage = new FakeStorage(myContext);
        try (Stream<Path> files = Files.list(MOCK_EXPORT)) {
            for (Path file : files.sorted().toList()) {
                for (String line : Files.readAllLines(file)) {
                    if (!line.isBlank()) {
                        myRecords.add(myJson.readTree(line));
                        myStorage.add(myContext.newJsonParser().parseResource(line));
                    }
                }
            }
        }
        String composition = Files.readString(COMPOSITION_FILE);
        myRecords.add(myJson.readTree(composition));
        myStorage.add(myContext.newJsonParser().parseResource(composition));
    }

    @Test
    void patientDrivenExportProducesTheSameRowsAsTheInMemoryMapping() throws IOException {
        List<List<String>> expected = referenceRows();

        ConvertedFiles files = converter(EcdsConverterConfig.defaults())
                .consume(list(patients()), parameters("text/csv", "Patient"));

        List<List<String>> actual = new ArrayList<>();
        for (ConvertedFile file : files.getFiles()) {
            if ("Patient".equals(file.getResourceType())) {
                actual.addAll(rows(file));
            }
        }
        actual.sort(Comparator.comparing(row -> String.join("\u001f", row)));
        assertThat(expected).isNotEmpty();
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Test
    void resourceDrivenExportProducesTheSameRowsToo() throws IOException {
        List<List<String>> expected = referenceRows();
        EcdsResourceConverter converter = converter(EcdsConverterConfig.defaults());

        List<List<String>> actual = new ArrayList<>();
        for (List<IBaseResource> chunk : chunksByType(2)) {
            String type = myContext.getResourceType(chunk.get(0));
            if (!EcdsRowBuilder.producesRows(type)) {
                continue; // a resource-driven export lists only the clinical types
            }
            for (ConvertedFile file : converter.consume(list(chunk), parameters("text/csv", type)).getFiles()) {
                if (file.getResourceType().equals(type)) {
                    actual.addAll(rows(file));
                }
            }
        }
        actual.sort(Comparator.comparing(row -> String.join("\u001f", row)));
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Test
    void clinicalChunksYieldNothingWhenTheExportAlsoCoversPatients() throws IOException {
        // _type=Patient,Condition would otherwise produce every Condition row twice.
        EcdsResourceConverter converter = converter(EcdsConverterConfig.defaults());
        List<IBaseResource> conditions = chunksByType(100).stream()
                .filter(chunk -> "Condition".equals(myContext.getResourceType(chunk.get(0))))
                .findFirst().orElseThrow();

        ConvertedFiles files = converter.consume(list(conditions), parameters("text/csv", "Patient", "Condition"));

        assertThat(files.getFiles()).hasSize(1);
        assertThat(rows(files.getFiles().get(0))).isEmpty();
    }

    @Test
    void aMembersRowsAreGroupedAndOrderedByType() throws IOException {
        ConvertedFiles files = converter(EcdsConverterConfig.defaults())
                .consume(list(patients()), parameters("text/csv", "Patient"));

        List<String> types = rows(files.getFiles().get(0)).stream()
                .map(row -> row.get(EcdsColumn.Ecdsaltid_00.ordinal()).split("/")[0])
                .distinct()
                .toList();
        assertThat(types).containsExactly("Encounter", "Condition", "Observation", "Procedure");
    }

    @Test
    void filesAreSplitAtTheConfiguredSize() throws IOException {
        ConvertedFiles files = converter(EcdsConverterConfig.defaults().withMaxFileBytes(2_000))
                .consume(list(patients()), parameters("text/csv", "Patient"));

        List<ConvertedFile> data = files.getFiles().stream()
                .filter(file -> "Patient".equals(file.getResourceType())).toList();
        assertThat(data.size()).isGreaterThan(1);
        int total = 0;
        for (ConvertedFile file : data) {
            assertThat(new String(file.getBytes(), StandardCharsets.UTF_8))
                    .startsWith(String.join(",", EcdsColumn.header()) + "\r\n");
            total += rows(file).size();
        }
        assertThat(total).isEqualTo(referenceRows().size());
    }

    @Test
    void auditEntriesComeBackAsASecondFile() {
        ConvertedFiles files = converter(EcdsConverterConfig.defaults())
                .consume(list(patients()), parameters("text/csv", "Patient"));

        ConvertedFile audit = files.getFiles().get(files.getFiles().size() - 1);
        assertThat(audit.getResourceType()).isEqualTo(EcdsConverterConfig.DEFAULT_AUDIT_TYPE);
        String content = new String(audit.getBytes(), StandardCharsets.UTF_8);
        assertThat(content).startsWith("severity,code,resourceType,resourceId,column,detail");
        assertThat(content).contains("REQUIRED_FIELD_MISSING");
    }

    @Test
    void theAuditFileCanBeSwitchedOff() {
        ConvertedFiles files = converter(EcdsConverterConfig.defaults().withAuditType(""))
                .consume(list(patients()), parameters("text/csv", "Patient"));

        assertThat(files.getFiles()).allMatch(file -> "Patient".equals(file.getResourceType()));
    }

    @Test
    void aChunkOfANonRowTypeStillReturnsAHeaderOnlyFile() throws IOException {
        Practitioner practitioner = new Practitioner();
        practitioner.setId("Practitioner/x");

        ConvertedFiles files = converter(EcdsConverterConfig.defaults())
                .consume(list(List.of(practitioner)), parameters("text/csv", "Practitioner"));

        assertThat(files.getFiles()).hasSize(1);
        assertThat(files.getFiles().get(0).getMimeType()).isEqualTo("text/csv");
        assertThat(rows(files.getFiles().get(0))).isEmpty();
    }

    @Test
    void aPatientWithNoRecordsYieldsAHeaderOnlyFileAndNoAudit() throws IOException {
        Patient lonely = new Patient();
        lonely.setId("Patient/nobody");
        myStorage.add(lonely);

        ConvertedFiles files = converter(EcdsConverterConfig.defaults())
                .consume(list(List.of(lonely)), parameters("text/csv", "Patient"));

        assertThat(files.getFiles()).hasSize(1);
        assertThat(rows(files.getFiles().get(0))).isEmpty();
    }

    @Test
    void concurrentChunksDoNotInterfere() throws Exception {
        // The export job hands chunks to one shared converter on several batch worker threads.
        // Nothing per-call may live in a field, and the shared caches have to be safe.
        EcdsResourceConverter converter = converter(EcdsConverterConfig.defaults());
        List<List<String>> expected = referenceRows();
        int threads = 4;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<List<String>>>> runs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    ConvertedFiles files = converter.consume(list(patients()), parameters("text/csv", "Patient"));
                    List<List<String>> rows = new ArrayList<>();
                    for (ConvertedFile file : files.getFiles()) {
                        if ("Patient".equals(file.getResourceType())) {
                            rows.addAll(rows(file));
                        }
                    }
                    rows.sort(Comparator.comparing(row -> String.join("\u001f", row)));
                    return rows;
                }));
            }
            start.countDown();
            for (Future<List<List<String>>> run : runs) {
                assertThat(run.get(60, TimeUnit.SECONDS)).containsExactlyElementsOf(expected);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(converter.chunksConverted()).isEqualTo(threads);
        assertThat(converter.rowsWritten()).isEqualTo((long) threads * expected.size());
    }

    @Test
    void thePatientInTheChunkIsNeverReadBackFromStorage() {
        DaoResourceResolver resolver = new DaoResourceResolver(myStorage.registry(), myContext, myRequest, 100);
        EcdsResourceConverter converter = new EcdsResourceConverter(myContext, resolver,
                new DaoDocumentResolver(myStorage.registry(), myRequest, 100),
                new PatientCompartmentSource(myStorage.registry(), myContext, myRequest, 50),
                EcdsConverterConfig.defaults());

        converter.consume(list(patients()), parameters("text/csv", "Patient"));

        assertThat(resolver.unresolvedReferences()).isEmpty();
        // The point of prime(): reads happen for Practitioners and Encounters, and for no
        // Patient at all. Asserting only that reads() is positive would hold either way.
        assertThat(resolver.reads()).isPositive();
        assertThat(myStorage.readsOf("Patient")).isZero();
        assertThat(myStorage.readsOf("Encounter")).isPositive();
    }

    @Test
    void withoutPrimingThePatientIsReadBackFromStorage() {
        // The negative control for the test above: the same walk, with the chunk's Patient not
        // seeded, does read it back — so readsOf("Patient") == 0 is pinning prime(), not an
        // accident of the fixture.
        DaoResourceResolver resolver = new DaoResourceResolver(myStorage.registry(), myContext, myRequest, 100);
        EcdsRowBuilder builder = new EcdsRowBuilder(FeedConstants.defaultFeed(), resolver,
                new DaoDocumentResolver(myStorage.registry(), myRequest, 100), null);
        new PatientCompartmentSource(myStorage.registry(), myContext, myRequest, 50)
                .forEachRecord("Patient/m1", builder::build);

        assertThat(myStorage.readsOf("Patient")).isPositive();
    }

    // --- helpers ---------------------------------------------------------------------------

    /** The POC's mapping over the same records, through the in-memory indexes. */
    private List<List<String>> referenceRows() {
        ReferenceIndex index = new ReferenceIndex();
        CompositionIndex documents = new CompositionIndex();
        for (JsonNode record : myRecords) {
            index.add(record);
            documents.add(record);
            documents.addReferrer(record);
        }
        documents.linkReferrers();
        EcdsRowBuilder builder = new EcdsRowBuilder(FeedConstants.defaultFeed(), index, documents, null);
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode record : myRecords) {
            for (EcdsRow row : builder.build(record)) {
                rows.add(row.toValues());
            }
        }
        rows.sort(Comparator.comparing(row -> String.join("\u001f", row)));
        return rows;
    }

    private EcdsResourceConverter converter(EcdsConverterConfig theConfig) {
        return new EcdsResourceConverter(myContext,
                new DaoResourceResolver(myStorage.registry(), myContext, myRequest, theConfig.cacheSize()),
                new DaoDocumentResolver(myStorage.registry(), myRequest, theConfig.cacheSize()),
                new PatientCompartmentSource(myStorage.registry(), myContext, myRequest, 2), // tiny pages: exercise paging
                theConfig);
    }

    private List<IBaseResource> patients() {
        return chunksByType(1000).stream()
                .filter(chunk -> "Patient".equals(myContext.getResourceType(chunk.get(0))))
                .findFirst().orElseThrow();
    }

    private List<List<IBaseResource>> chunksByType(int theChunkSize) {
        Map<String, List<IBaseResource>> byType = new LinkedHashMap<>();
        for (JsonNode record : myRecords) {
            IBaseResource resource = myContext.newJsonParser().parseResource(record.toString());
            byType.computeIfAbsent(myContext.getResourceType(resource), k -> new ArrayList<>()).add(resource);
        }
        List<List<IBaseResource>> chunks = new ArrayList<>();
        for (List<IBaseResource> resources : byType.values()) {
            for (int from = 0; from < resources.size(); from += theChunkSize) {
                chunks.add(resources.subList(from, Math.min(resources.size(), from + theChunkSize)));
            }
        }
        return chunks;
    }

    private static BulkExportResourceList list(List<IBaseResource> theResources) {
        BulkExportResourceList list = new BulkExportResourceList();
        list.setResources(theResources);
        return list;
    }

    private static BulkExportJobParameters parameters(String theOutputFormat, String... theTypes) {
        BulkExportJobParameters parameters = new BulkExportJobParameters();
        parameters.setOutputFormat(theOutputFormat);
        parameters.setResourceTypes(List.of(theTypes));
        return parameters;
    }

    private static List<List<String>> rows(ConvertedFile theFile) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (Reader reader = new StringReader(new String(theFile.getBytes(), StandardCharsets.UTF_8));
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
            assertThat(parser.getHeaderNames()).isEqualTo(EcdsColumn.header());
            for (CSVRecord record : parser) {
                rows.add(record.toList());
            }
        }
        return rows;
    }
}
