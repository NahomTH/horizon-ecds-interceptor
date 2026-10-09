package org.horizon.cdr;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class DaoResourceResolverTest {

    private final FhirContext myContext = FhirContext.forR4Cached();
    private final Supplier<RequestDetails> myRequest = SystemRequestDetails::new;

    @Test
    void readsAResourceAndHandsBackTheSameJsonTheExportWouldHaveCarried() {
        Patient patient = new Patient();
        patient.setId("Patient/2518");
        patient.addIdentifier(new Identifier()
                .setSystem("http://horizonblue.com/fhir/patient/identifier/horizon-id")
                .setValue("11111"));
        FakeStorage storage = new FakeStorage(myContext).add(patient);
        DaoResourceResolver resolver = new DaoResourceResolver(storage.registry(), myContext, myRequest, 10);

        Optional<JsonNode> found = resolver.resolve("Patient/2518", null);

        assertThat(found).isPresent();
        assertThat(found.get().path("resourceType").asText()).isEqualTo("Patient");
        assertThat(found.get().path("identifier").get(0).path("value").asText()).isEqualTo("11111");
    }

    @Test
    void cachesSoRepeatedReferencesCostOneRead() {
        Patient patient = new Patient();
        patient.setId("Patient/1");
        FakeStorage storage = new FakeStorage(myContext).add(patient);
        DaoResourceResolver resolver = new DaoResourceResolver(storage.registry(), myContext, myRequest, 10);

        resolver.resolve("Patient/1");
        resolver.resolve("Patient/1");
        resolver.resolve("http://example.org/fhir/Patient/1");

        assertThat(resolver.reads()).isEqualTo(1);
    }

    @Test
    void aMissingResourceIsEmptyAndRemembered() {
        DaoResourceResolver resolver = new DaoResourceResolver(
                new FakeStorage(myContext).registry(), myContext, myRequest, 10);

        assertThat(resolver.resolve("Patient/missing")).isEmpty();
        assertThat(resolver.resolve("Patient/missing")).isEmpty();
        assertThat(resolver.unresolvedReferences()).containsExactly("Patient/missing");
        assertThat(resolver.reads()).as("the miss is cached too").isEqualTo(1);
    }

    @Test
    void aMalformedReferenceIsEmptyWithoutTouchingStorage() {
        DaoResourceResolver resolver = new DaoResourceResolver(
                new FakeStorage(myContext).registry(), myContext, myRequest, 10);

        assertThat(resolver.resolve("not-a-reference")).isEmpty();
        assertThat(resolver.resolve("")).isEmpty();
        assertThat(resolver.resolve(null, null)).isEmpty();
        assertThat(resolver.reads()).isZero();
    }

    @Test
    void containedReferencesStayLocalToTheirParent() throws Exception {
        JsonNode parent = new ObjectMapper().readTree("""
                {"resourceType":"MedicationRequest","id":"1",
                 "contained":[{"resourceType":"Medication","id":"med-1","code":{"text":"x"}}]}
                """);
        DaoResourceResolver resolver = new DaoResourceResolver(
                new FakeStorage(myContext).registry(), myContext, myRequest, 10);

        assertThat(resolver.resolve("#med-1", parent)).isPresent();
        assertThat(resolver.resolve("#med-1", null)).isEmpty();
        assertThat(resolver.reads()).isZero();
    }
}
