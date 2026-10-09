package org.horizon.ecds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.horizon.fhir.CompositionIndex;
import org.horizon.fhir.ReferenceIndex;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderColumnsTest {

    private final ObjectMapper myMapper = new ObjectMapper();
    private final ReferenceIndex myIndex = new ReferenceIndex();
    private final CompositionIndex myDocuments = new CompositionIndex();

    @Test
    void providerKeyIsTheNpiOfTheResolvedPerformer() {
        myIndex.add(parse("""
                {"resourceType":"Practitioner","id":"2626",
                 "identifier":[{"system":"http://hl7.org/fhir/sid/us-npi","value":"1326740598"}]}
                """));
        EcdsRow row = apply("""
                {"resourceType":"Procedure","id":"1","performer":[{"actor":{"reference":"Practitioner/2626"}}]}
                """);

        assertThat(row.get(EcdsColumn.ProviderKey)).isEqualTo("1326740598");
    }

    @Test
    void theNpiValueIsEmittedVerbatimWithoutValidation() {
        // Practitioner/2622 carries '25' under the NPI system. Per the user's decision the
        // value ships as-is; Horizon validates downstream.
        myIndex.add(parse("""
                {"resourceType":"Practitioner","id":"2622",
                 "identifier":[{"system":"http://hl7.org/fhir/sid/us-npi","value":"25"}]}
                """));
        EcdsRow row = apply("""
                {"resourceType":"Procedure","id":"1","performer":[{"actor":{"reference":"Practitioner/2622"}}]}
                """);

        assertThat(row.get(EcdsColumn.ProviderKey)).isEqualTo("25");
    }

    @Test
    void aPerformerWithNoNpiLeavesProviderKeyBlank() {
        myIndex.add(parse("{\"resourceType\":\"Practitioner\",\"id\":\"2633\"}"));
        EcdsRow row = apply("""
                {"resourceType":"Procedure","id":"1","performer":[{"actor":{"reference":"Practitioner/2633"}}]}
                """);

        assertThat(row.get(EcdsColumn.ProviderKey)).isEmpty();
    }

    @Test
    void providerKeyFallsBackToAnOrganizationTaxId() {
        myIndex.add(parse("""
                {"resourceType":"Organization","id":"2625",
                 "identifier":[{"system":"urn:oid:2.16.840.1.113883.4.4","value":"22-1234567"}]}
                """));
        EcdsRow row = apply("""
                {"resourceType":"Procedure","id":"1","performer":[{"actor":{"reference":"Organization/2625"}}]}
                """);

        assertThat(row.get(EcdsColumn.ProviderKey)).isEqualTo("22-1234567");
    }

    @Test
    void aRecordWithNoPerformerFallsBackToItsEncountersPractitioner() {
        // A Condition names no performer of its own, but the encounter it belongs to has a
        // participating practitioner — that is the provider for the event.
        myIndex.add(parse("""
                {"resourceType":"Practitioner","id":"2662",
                 "identifier":[{"system":"http://hl7.org/fhir/sid/us-npi","value":"1821016676"}]}
                """));
        myIndex.add(parse("""
                {"resourceType":"Encounter","id":"2676",
                 "participant":[{"individual":{"reference":"Practitioner/2662"}}]}
                """));
        EcdsRow row = apply("""
                {"resourceType":"Condition","id":"2678","encounter":{"reference":"Encounter/2676"}}
                """);

        assertThat(row.get(EcdsColumn.ProviderKey)).isEqualTo("1821016676");
    }

    @Test
    void anEncountersOwnParticipantIsUsedForEncounterRows() {
        myIndex.add(parse("""
                {"resourceType":"Practitioner","id":"p",
                 "identifier":[{"system":"http://hl7.org/fhir/sid/us-npi","value":"1326740598"}]}
                """));
        EcdsRow row = apply("""
                {"resourceType":"Encounter","id":"1",
                 "participant":[{"individual":{"reference":"Practitioner/p"}}]}
                """);

        assertThat(row.get(EcdsColumn.ProviderKey)).isEqualTo("1326740598");
    }

    @Test
    void aDirectPerformerIsPreferredOverTheEncounterParticipant() {
        myIndex.add(parse("""
                {"resourceType":"Practitioner","id":"direct",
                 "identifier":[{"system":"http://hl7.org/fhir/sid/us-npi","value":"1111111111"}]}
                """));
        myIndex.add(parse("""
                {"resourceType":"Practitioner","id":"viaEnc",
                 "identifier":[{"system":"http://hl7.org/fhir/sid/us-npi","value":"2222222222"}]}
                """));
        myIndex.add(parse("""
                {"resourceType":"Encounter","id":"9",
                 "participant":[{"individual":{"reference":"Practitioner/viaEnc"}}]}
                """));
        EcdsRow row = apply("""
                {"resourceType":"Procedure","id":"1",
                 "performer":[{"actor":{"reference":"Practitioner/direct"}}],
                 "encounter":{"reference":"Encounter/9"}}
                """);

        assertThat(row.get(EcdsColumn.ProviderKey)).isEqualTo("1111111111");
    }

    @Test
    void theFirstParticipantCarryingAnNpiIsUsed() {
        myIndex.add(parse("{\"resourceType\":\"Practitioner\",\"id\":\"noNpi\"}"));
        myIndex.add(parse("""
                {"resourceType":"Practitioner","id":"withNpi",
                 "identifier":[{"system":"http://hl7.org/fhir/sid/us-npi","value":"1326740598"}]}
                """));
        myIndex.add(parse("""
                {"resourceType":"Encounter","id":"9","participant":[
                  {"individual":{"reference":"Practitioner/noNpi"}},
                  {"individual":{"reference":"Practitioner/withNpi"}}]}
                """));
        EcdsRow row = apply("""
                {"resourceType":"Condition","id":"1","encounter":{"reference":"Encounter/9"}}
                """);

        assertThat(row.get(EcdsColumn.ProviderKey)).isEqualTo("1326740598");
    }

    @Test
    void aRecordWithNoPerformerAndNoEncounterLeavesProviderKeyBlank() {
        assertThat(apply("{\"resourceType\":\"Observation\",\"id\":\"1\"}")
                .get(EcdsColumn.ProviderKey)).isEmpty();
    }

    @Test
    void anEncounterWhoseParticipantsHaveNoNpiLeavesProviderKeyBlank() {
        myIndex.add(parse("{\"resourceType\":\"Practitioner\",\"id\":\"p\"}"));
        myIndex.add(parse("""
                {"resourceType":"Encounter","id":"9",
                 "participant":[{"individual":{"reference":"Practitioner/p"}}]}
                """));

        assertThat(apply("""
                {"resourceType":"Condition","id":"1","encounter":{"reference":"Encounter/9"}}
                """).get(EcdsColumn.ProviderKey)).isEmpty();
    }

    @Test
    void eachResourceTypeUsesItsOwnPerformerElement() {
        myIndex.add(parse("""
                {"resourceType":"Practitioner","id":"p",
                 "identifier":[{"system":"http://hl7.org/fhir/sid/us-npi","value":"1326740598"}]}
                """));

        assertThat(apply("""
                {"resourceType":"Observation","id":"1","performer":[{"reference":"Practitioner/p"}]}
                """).get(EcdsColumn.ProviderKey)).isEqualTo("1326740598");
        assertThat(apply("""
                {"resourceType":"Immunization","id":"1","performer":[{"actor":{"reference":"Practitioner/p"}}]}
                """).get(EcdsColumn.ProviderKey)).isEqualTo("1326740598");
        assertThat(apply("""
                {"resourceType":"Encounter","id":"1","participant":[{"individual":{"reference":"Practitioner/p"}}]}
                """).get(EcdsColumn.ProviderKey)).isEqualTo("1326740598");
    }

    @Test
    void aRecordWithNoEncounterFallsBackToTheEncounterOfItsSourceDocument() {
        // Procedures in the export name neither a performer with an NPI nor an encounter,
        // but the document that carried them describes one visit.
        myIndex.add(parse("""
                {"resourceType":"Practitioner","id":"2662",
                 "identifier":[{"system":"http://hl7.org/fhir/sid/us-npi","value":"1821016676"}]}
                """));
        myIndex.add(parse("""
                {"resourceType":"Encounter","id":"3162",
                 "participant":[{"individual":{"reference":"Practitioner/2662"}}]}
                """));
        myDocuments.add(parse("""
                {"resourceType":"Composition","id":"3154","identifier":{"value":"urn:uuid:doc"},
                 "encounter":{"reference":"Encounter/3162"},
                 "section":[{"entry":[{"reference":"Procedure/1"}]}]}
                """));

        EcdsRow row = apply("{\"resourceType\":\"Procedure\",\"id\":\"1\"}");

        assertThat(row.get(EcdsColumn.ProviderKey)).isEqualTo("1821016676");
    }

    @Test
    void theRecordsOwnEncounterIsPreferredOverTheDocumentEncounter() {
        myIndex.add(parse("""
                {"resourceType":"Practitioner","id":"own",
                 "identifier":[{"system":"http://hl7.org/fhir/sid/us-npi","value":"1111111111"}]}
                """));
        myIndex.add(parse("""
                {"resourceType":"Practitioner","id":"doc",
                 "identifier":[{"system":"http://hl7.org/fhir/sid/us-npi","value":"2222222222"}]}
                """));
        myIndex.add(parse("""
                {"resourceType":"Encounter","id":"own",
                 "participant":[{"individual":{"reference":"Practitioner/own"}}]}
                """));
        myIndex.add(parse("""
                {"resourceType":"Encounter","id":"doc",
                 "participant":[{"individual":{"reference":"Practitioner/doc"}}]}
                """));
        myDocuments.add(parse("""
                {"resourceType":"Composition","id":"1","identifier":{"value":"urn:uuid:d"},
                 "encounter":{"reference":"Encounter/doc"},
                 "section":[{"entry":[{"reference":"Condition/1"}]}]}
                """));

        EcdsRow row = apply("""
                {"resourceType":"Condition","id":"1","encounter":{"reference":"Encounter/own"}}
                """);

        assertThat(row.get(EcdsColumn.ProviderKey)).isEqualTo("1111111111");
    }

    @Test
    void theDocumentAttesterIsNotUsedAsAProvider() {
        // An attester signed the document; that is not a claim about who performed the event.
        myIndex.add(parse("""
                {"resourceType":"Practitioner","id":"signer",
                 "identifier":[{"system":"http://hl7.org/fhir/sid/us-npi","value":"1326740598"}]}
                """));
        myDocuments.add(parse("""
                {"resourceType":"Composition","id":"1","identifier":{"value":"urn:uuid:d"},
                 "attester":[{"mode":"legal","party":{"reference":"Practitioner/signer"}}],
                 "section":[{"entry":[{"reference":"Procedure/1"}]}]}
                """));

        assertThat(apply("{\"resourceType\":\"Procedure\",\"id\":\"1\"}")
                .get(EcdsColumn.ProviderKey)).isEmpty();
    }

    @Test
    void taxonomyColumnsStayBlankWhenTheSourceCarriesNoNuccCode() {
        myIndex.add(parse("{\"resourceType\":\"Practitioner\",\"id\":\"p\"}"));
        EcdsRow row = apply("""
                {"resourceType":"Procedure","id":"1","performer":[{"actor":{"reference":"Practitioner/p"}}]}
                """);

        assertThat(row.get(EcdsColumn.ProviderTaxonomy)).isEmpty();
        assertThat(row.get(EcdsColumn.ProviderType_00)).isEmpty();
    }

    // --- ProviderType_00..05 ------------------------------------------------

    @Test
    void providerTypeIsCrosswalkedFromASingleTaxonomy() {
        EcdsRow row = applyWithTaxonomies("207Q00000X");

        assertThat(row.get(EcdsColumn.ProviderType_00)).isEqualTo("PCP");
        assertThat(row.get(EcdsColumn.ProviderType_01)).isEmpty();
    }

    @Test
    void severalTaxonomiesFillTheProviderTypeColumnsInSourceOrder() {
        EcdsRow row = applyWithTaxonomies("207RA0001X", "207Q00000X", "364S00000X");

        assertThat(row.get(EcdsColumn.ProviderType_00)).isEqualTo("CARD");
        assertThat(row.get(EcdsColumn.ProviderType_01)).isEqualTo("PCP");
        assertThat(row.get(EcdsColumn.ProviderType_02)).isEqualTo("NPCP");
        assertThat(row.get(EcdsColumn.ProviderType_03)).isEmpty();
    }

    @Test
    void twoTaxonomiesMappingToTheSameTypeFillOnlyOneColumn() {
        // Horizon's rule: do not repeat the _00 value. Both of these crosswalk to CARD.
        EcdsRow row = applyWithTaxonomies("207RA0001X", "207RC0000X");

        assertThat(row.get(EcdsColumn.ProviderType_00)).isEqualTo("CARD");
        assertThat(row.get(EcdsColumn.ProviderType_01)).isEmpty();
    }

    @Test
    void moreThanSixDistinctTypesFillOnlyTheSixColumns() {
        EcdsRow row = applyWithTaxonomies("207RA0001X", "207Q00000X", "364S00000X",
                "183500000X", "204E00000X", "207RG0100X", "207RE0101X");

        assertThat(row.get(EcdsColumn.ProviderType_00)).isEqualTo("CARD");
        assertThat(row.get(EcdsColumn.ProviderType_05)).isEqualTo("GAST");
        // ENDO, the seventh, has nowhere to go.
    }

    @Test
    void anUnmappedTaxonomyLeavesTheProviderTypeColumnsBlank() {
        EcdsRow row = applyWithTaxonomies("999X00000X");

        assertThat(row.get(EcdsColumn.ProviderType_00)).isEmpty();
        assertThat(row.get(EcdsColumn.ProviderTaxonomy)).isEqualTo("999X00000X");
    }

    @Test
    void anUnmappedTaxonomyDoesNotDisplaceAMappedOne() {
        EcdsRow row = applyWithTaxonomies("999X00000X", "207Q00000X");

        assertThat(row.get(EcdsColumn.ProviderType_00)).isEqualTo("PCP");
    }

    @Test
    void aProviderWithNoTaxonomyLeavesTheProviderTypeColumnsBlank() {
        myIndex.add(parse("{\"resourceType\":\"Practitioner\",\"id\":\"p\"}"));
        EcdsRow row = apply("""
                {"resourceType":"Procedure","id":"1","performer":[{"actor":{"reference":"Practitioner/p"}}]}
                """);

        assertThat(row.get(EcdsColumn.ProviderType_00)).isEmpty();
        assertThat(row.get(EcdsColumn.ProviderTaxonomy)).isEmpty();
    }

    @Test
    void providerTaxonomyStillEmitsASingleCode() {
        // The column is singular: the first taxonomy, even when the provider has several.
        EcdsRow row = applyWithTaxonomies("207RA0001X", "207Q00000X");

        assertThat(row.get(EcdsColumn.ProviderTaxonomy)).isEqualTo("207RA0001X");
    }

    /** A Procedure whose performer carries the given NUCC taxonomy codes. */
    private EcdsRow applyWithTaxonomies(String... theNuccCodes) {
        StringBuilder qualifications = new StringBuilder();
        for (String code : theNuccCodes) {
            qualifications.append(qualifications.length() > 0 ? "," : "")
                    .append("{\"code\":{\"coding\":[{\"system\":\"http://nucc.org/provider-taxonomy\",")
                    .append("\"code\":\"").append(code).append("\"}]}}");
        }
        myIndex.add(parse("{\"resourceType\":\"Practitioner\",\"id\":\"p\","
                + "\"identifier\":[{\"system\":\"http://hl7.org/fhir/sid/us-npi\",\"value\":\"1326740598\"}],"
                + "\"qualification\":[" + qualifications + "]}"));
        return apply("""
                {"resourceType":"Procedure","id":"1","performer":[{"actor":{"reference":"Practitioner/p"}}]}
                """);
    }

    @Test
    void taxonomyComesFromPractitionerQualification() {
        myIndex.add(parse("""
                {"resourceType":"Practitioner","id":"p","qualification":[
                  {"code":{"coding":[{"system":"http://nucc.org/provider-taxonomy","code":"207Q00000X"}]}}]}
                """));
        EcdsRow row = apply("""
                {"resourceType":"Procedure","id":"1","performer":[{"actor":{"reference":"Practitioner/p"}}]}
                """);

        assertThat(row.get(EcdsColumn.ProviderTaxonomy)).isEqualTo("207Q00000X");
    }

    // --- RxProviderFlag / QuantityDispensed --------------------------------

    @Test
    void rxProviderFlagIsZeroWhenNoPrescriberNpiIsReachable() {
        // Every requester in the export is a Device or Organization.
        EcdsRow row = apply("""
                {"resourceType":"MedicationRequest","id":"1","requester":{"reference":"Organization/2625"}}
                """);

        assertThat(row.get(EcdsColumn.RxProviderFlag)).isEqualTo("0");
    }

    @Test
    void rxProviderFlagIsOneWhenThePrescriberHasAnNpi() {
        myIndex.add(parse("""
                {"resourceType":"Practitioner","id":"p",
                 "identifier":[{"system":"http://hl7.org/fhir/sid/us-npi","value":"1326740598"}]}
                """));
        EcdsRow row = apply("""
                {"resourceType":"MedicationRequest","id":"1","requester":{"reference":"Practitioner/p"}}
                """);

        assertThat(row.get(EcdsColumn.RxProviderFlag)).isEqualTo("1");
    }

    @Test
    void rxProviderFlagIgnoresAnNpiThatOnlyTheEncounterCarries() {
        // The spec names one source: "MedicationRequest.requester resolving to
        // Practitioner.identifier where system='http://hl7.org/fhir/sid/us-npi'". ProviderKey
        // may walk out to the visit for a provider; this flag may not, or a 1 would claim a
        // prescriber NPI that the medication entry never named.
        myIndex.add(parse("""
                {"resourceType":"Practitioner","id":"p",
                 "identifier":[{"system":"http://hl7.org/fhir/sid/us-npi","value":"1326740598"}]}
                """));
        myIndex.add(parse("""
                {"resourceType":"Encounter","id":"e",
                 "participant":[{"individual":{"reference":"Practitioner/p"}}]}
                """));
        EcdsRow row = apply("""
                {"resourceType":"MedicationRequest","id":"1","encounter":{"reference":"Encounter/e"},
                 "requester":{"reference":"Organization/2625"}}
                """);

        assertThat(row.get(EcdsColumn.RxProviderFlag)).isEqualTo("0");
        // ProviderKey still reaches it — the two columns ask different questions.
        assertThat(row.get(EcdsColumn.ProviderKey)).isEqualTo("1326740598");
    }

    @Test
    void rxProviderFlagDefaultsToZeroOnNonMedicationRows() {
        // Required=Yes in the spec, and the transform says default to 0 when no prescriber
        // NPI is present — which a non-medication row never has.
        assertThat(apply("{\"resourceType\":\"Observation\",\"id\":\"1\"}")
                .get(EcdsColumn.RxProviderFlag)).isEqualTo("0");
        assertThat(apply("{\"resourceType\":\"Condition\",\"id\":\"1\"}")
                .get(EcdsColumn.RxProviderFlag)).isEqualTo("0");
    }

    @Test
    void quantityDispensedComesFromTheDispenseRequest() {
        EcdsRow row = apply("""
                {"resourceType":"MedicationRequest","id":"1",
                 "dispenseRequest":{"quantity":{"value":90}}}
                """);

        assertThat(row.get(EcdsColumn.QuantityDispensed)).isEqualTo("90");
    }

    @Test
    void quantityDispensedDefaultsToOneWhenAbsentOrZero() {
        // The column must not be zero.
        assertThat(apply("{\"resourceType\":\"MedicationRequest\",\"id\":\"1\"}")
                .get(EcdsColumn.QuantityDispensed)).isEqualTo("1");
        assertThat(apply("""
                {"resourceType":"MedicationRequest","id":"1","dispenseRequest":{"quantity":{"value":0}}}
                """).get(EcdsColumn.QuantityDispensed)).isEqualTo("1");
    }

    @Test
    void placeOfServiceComesFromTheEncounterARecordBelongsTo() {
        // Required on every row, not only Encounter rows, so a Condition takes the place of
        // service of the visit it belongs to.
        myIndex.add(parse("""
                {"resourceType":"Encounter","id":"9","class":{"code":"EMER"}}
                """));
        EcdsRow row = apply("""
                {"resourceType":"Condition","id":"1","encounter":{"reference":"Encounter/9"}}
                """);

        assertThat(row.get(EcdsColumn.HCFAPOS_00)).isEqualTo("23");
    }

    @Test
    void placeOfServiceIsAuditedWhenItCannotBeDerived() {
        // Required=Yes, but every Encounter.class in the export is data-absent. The gap is
        // reported rather than filled with a placebo code.
        AuditLog audit = AuditLog.discarding();
        EcdsRow row = new EcdsRow();
        ProviderColumns.applyTo(parse("""
                {"resourceType":"Encounter","id":"1","class":{"extension":[
                  {"url":"http://hl7.org/fhir/StructureDefinition/data-absent-reason","valueCode":"unknown"}]}}
                """), myIndex, myDocuments, row, audit);

        assertThat(row.get(EcdsColumn.HCFAPOS_00)).isEmpty();
        assertThat(audit.counts()).containsKey(AuditCode.REQUIRED_FIELD_MISSING);
    }

    @Test
    void aDerivablePlaceOfServiceIsNotAudited() {
        AuditLog audit = AuditLog.discarding();
        EcdsRow row = new EcdsRow();
        ProviderColumns.applyTo(parse("""
                {"resourceType":"Encounter","id":"1","class":{"code":"AMB"}}
                """), myIndex, myDocuments, row, audit);

        assertThat(row.get(EcdsColumn.HCFAPOS_00)).isEqualTo("11");
        assertThat(audit.counts()).doesNotContainKey(AuditCode.REQUIRED_FIELD_MISSING);
    }

    @Test
    void quantityDispensedIsBlankOnNonPharmacyRows() {
        assertThat(apply("{\"resourceType\":\"Observation\",\"id\":\"1\"}")
                .get(EcdsColumn.QuantityDispensed)).isEmpty();
    }

    // --- HCFAPOS_00 ---------------------------------------------------------

    @Test
    void placeOfServiceIsCrosswalkedFromTheEncounterClass() {
        assertThat(posFor("AMB")).isEqualTo("11");
        assertThat(posFor("EMER")).isEqualTo("23");
        assertThat(posFor("IMP")).isEqualTo("21");
        assertThat(posFor("SS")).isEqualTo("24");
        assertThat(posFor("HH")).isEqualTo("12");
        assertThat(posFor("VR")).isEqualTo("02");
        assertThat(posFor("OBSENC")).isEqualTo("22");
    }

    @Test
    void placeOfServiceIsBlankWhenTheEncounterClassIsDataAbsent() {
        // Every Encounter in the export is in this shape.
        EcdsRow row = apply("""
                {"resourceType":"Encounter","id":"1","class":{"extension":[
                  {"url":"http://hl7.org/fhir/StructureDefinition/data-absent-reason","valueCode":"unknown"}]}}
                """);

        assertThat(row.get(EcdsColumn.HCFAPOS_00)).isEmpty();
    }

    @Test
    void anUnknownEncounterClassIsNotGuessedAt() {
        assertThat(posFor("ZZZ")).isEmpty();
    }

    private String posFor(String theClassCode) {
        return apply("""
                {"resourceType":"Encounter","id":"1","class":{"code":"%s"}}
                """.formatted(theClassCode)).get(EcdsColumn.HCFAPOS_00);
    }

    private EcdsRow apply(String theJson) {
        EcdsRow row = new EcdsRow();
        ProviderColumns.applyTo(parse(theJson), myIndex, myDocuments, row, AuditLog.discarding());
        return row;
    }

    private JsonNode parse(String theJson) {
        try {
            return myMapper.readTree(theJson);
        } catch (Exception e) {
            throw new IllegalArgumentException(theJson, e);
        }
    }
}
