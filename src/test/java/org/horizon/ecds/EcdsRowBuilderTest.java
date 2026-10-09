package org.horizon.ecds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.horizon.fhir.CompositionIndex;
import org.horizon.fhir.ReferenceIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The builder is exercised end to end by every {@code EcdsMapper} test; these pin the contract
 * the interceptor relies on directly.
 */
class EcdsRowBuilderTest {

    private final ObjectMapper myMapper = new ObjectMapper();
    private final ReferenceIndex myIndex = new ReferenceIndex();
    private final CompositionIndex myDocuments = new CompositionIndex();

    @Test
    void nonRowTypesYieldNothing() throws Exception {
        EcdsRowBuilder builder = new EcdsRowBuilder(FeedConstants.defaultFeed(), myIndex, myDocuments, null);

        assertThat(builder.build(parse("{\"resourceType\":\"Patient\",\"id\":\"1\"}"))).isEmpty();
        assertThat(builder.build(parse("{\"resourceType\":\"Practitioner\",\"id\":\"1\"}"))).isEmpty();
        assertThat(builder.build(null)).isEmpty();
    }

    @Test
    void buildsTheSharedColumnsThenExplodes() throws Exception {
        myIndex.add(parse("""
                {"resourceType":"Patient","id":"p1","gender":"female","birthDate":"2014-02-01",
                 "identifier":[{"system":"http://horizonblue.com/fhir/patient/identifier/horizon-id","value":"11111"}],
                 "name":[{"family":"Test","given":["Daffy"]}]}
                """));
        EcdsRowBuilder builder = new EcdsRowBuilder(FeedConstants.defaultFeed(), myIndex, myDocuments, null);

        List<EcdsRow> rows = builder.build(parse("""
                {"resourceType":"Procedure","id":"pr1","subject":{"reference":"Patient/p1"},
                 "performedDateTime":"2026-03-04",
                 "code":{"coding":[{"system":"http://www.ama-assn.org/go/cpt","code":"99213"},
                                   {"system":"http://www.ama-assn.org/go/cpt","code":"99214"}]}}
                """));

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> row.get(EcdsColumn.CPTPx)).containsExactly("99213", "99214");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get(EcdsColumn.MemberKey)).isEqualTo("11111");
            assertThat(row.get(EcdsColumn.DOS)).isEqualTo("2026-03-04");
            assertThat(row.get(EcdsColumn.ReferenceID)).isEqualTo("SMILEMY26");
            assertThat(row.get(EcdsColumn.Ecdsaltid_00)).isEqualTo("Procedure/pr1");
        });
    }

    @Test
    void countsOriginsAndUntaggedRecords() throws Exception {
        EcdsRowBuilder builder = new EcdsRowBuilder(FeedConstants.defaultFeed(), myIndex, myDocuments, null);

        builder.build(parse("""
                {"resourceType":"Condition","id":"1","meta":{"tag":[
                  {"system":"http://horizonblue.com/fhir/meta/tag/data-origin","code":"KonzaHealth"}]}}
                """));
        List<EcdsRow> untagged = builder.build(parse("{\"resourceType\":\"Condition\",\"id\":\"2\"}"));

        assertThat(builder.originCounts()).containsEntry("KonzaHealth", 1L);
        assertThat(builder.untaggedRecords()).isEqualTo(1);
        assertThat(untagged.get(0).get(EcdsColumn.ClientSystemOfRecordID)).isEqualTo("EHR");
    }

    @Test
    void auditsWhatItCannotResolve() throws Exception {
        AuditLog audit = AuditLog.discarding();
        EcdsRowBuilder builder = new EcdsRowBuilder(FeedConstants.defaultFeed(), myIndex, myDocuments, audit);

        builder.build(parse("""
                {"resourceType":"Condition","id":"1","subject":{"reference":"Patient/missing"}}
                """));

        assertThat(audit.counts())
                .containsKey(AuditCode.MEMBER_KEY_MISSING)
                .containsKey(AuditCode.DOS_MISSING);
    }

    private JsonNode parse(String theJson) throws Exception {
        return myMapper.readTree(theJson);
    }
}
