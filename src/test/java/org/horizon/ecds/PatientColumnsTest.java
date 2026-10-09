package org.horizon.ecds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Fixtures follow the real Patient/2518 shape from the sample export. */
class PatientColumnsTest {

    private static final String HORIZON = "http://horizonblue.com/fhir/patient/identifier/";
    private static final String BIRTHSEX =
            "http://hl7.org/fhir/us/core/StructureDefinition/us-core-birthsex";

    private final ObjectMapper myMapper = new ObjectMapper();

    // --- MemberKey / MemberID ---------------------------------------------

    @Test
    void memberKeyComesFromTheHorizonIdIdentifier() {
        JsonNode patient = parse("""
                {"resourceType":"Patient","id":"2518","identifier":[
                  {"system":"%shorizon-id","value":"11111"},
                  {"system":"%scard-id","value":"1234"}]}
                """.formatted(HORIZON, HORIZON));

        assertThat(columnsFor(patient).get(EcdsColumn.MemberKey)).isEqualTo("11111");
    }

    @Test
    void memberIdComesFromTheCardIdIdentifier() {
        JsonNode patient = parse("""
                {"resourceType":"Patient","id":"2518","identifier":[
                  {"system":"%shorizon-id","value":"11111"},
                  {"system":"%scard-id","value":"1234"}]}
                """.formatted(HORIZON, HORIZON));

        assertThat(columnsFor(patient).get(EcdsColumn.MemberID)).isEqualTo("1234");
    }

    @Test
    void memberIdFallsBackToTheMedicaidIdentifier() {
        JsonNode patient = parse("""
                {"resourceType":"Patient","id":"1","identifier":[
                  {"system":"%shorizon-id","value":"11111"},
                  {"system":"%smedicaid-id","value":"333333"}]}
                """.formatted(HORIZON, HORIZON));

        assertThat(columnsFor(patient).get(EcdsColumn.MemberID)).isEqualTo("333333");
    }

    @Test
    void memberIdPrefersAnIdentifierTypedMb() {
        // The spec's primary rule. No identifier in the sample export uses it, so it is
        // exercised here rather than by the real data.
        JsonNode patient = parse("""
                {"resourceType":"Patient","id":"1","identifier":[
                  {"system":"%shorizon-id","value":"11111"},
                  {"type":{"coding":[{"code":"MB"}]},"system":"urn:other","value":"MB-9"}]}
                """.formatted(HORIZON));

        assertThat(columnsFor(patient).get(EcdsColumn.MemberID)).isEqualTo("MB-9");
    }

    @Test
    void memberIdIsNeverTheSameValueAsMemberKey() {
        // Horizon's explicit warning: DO NOT POPULATE WITH MEMBERKEY.
        JsonNode patient = parse("""
                {"resourceType":"Patient","id":"1","identifier":[
                  {"system":"%shorizon-id","value":"11111"}]}
                """.formatted(HORIZON));

        PatientColumns columns = columnsFor(patient);

        assertThat(columns.get(EcdsColumn.MemberKey)).isEqualTo("11111");
        assertThat(columns.get(EcdsColumn.MemberID)).isEmpty();
    }

    @Test
    void memberKeyIsTruncatedToThirtyCharacters() {
        String long40 = "0123456789012345678901234567890123456789";
        JsonNode patient = parse("""
                {"resourceType":"Patient","id":"1","identifier":[
                  {"system":"%shorizon-id","value":"%s"}]}
                """.formatted(HORIZON, long40));

        assertThat(columnsFor(patient).get(EcdsColumn.MemberKey)).hasSize(30);
    }

    @Test
    void memberKeyFallsBackToAnotherIdentifierWhenTheHorizonSystemIsAbsent() {
        JsonNode patient = parse("""
                {"resourceType":"Patient","id":"1","identifier":[
                  {"system":"urn:uuid:abc","value":"56565655"}]}
                """);

        assertThat(columnsFor(patient).get(EcdsColumn.MemberKey)).isEqualTo("56565655");
    }

    // --- names -------------------------------------------------------------

    @Test
    void usesTheOfficialNameWhenOneIsPresent() {
        JsonNode patient = parse("""
                {"resourceType":"Patient","id":"1","name":[
                  {"use":"usual","family":"Nickname","given":["Nick"]},
                  {"use":"official","family":"Real","given":["Proper","Middle"]}]}
                """);

        PatientColumns columns = columnsFor(patient);

        assertThat(columns.get(EcdsColumn.MemberLastName)).isEqualTo("Real");
        assertThat(columns.get(EcdsColumn.MemberFirstName)).isEqualTo("Proper");
    }

    @Test
    void fallsBackToTheFirstNameEntryWhenNoNameIsOfficial() {
        // Patient/2518 carries use="usual", so this is the branch the real data takes.
        JsonNode patient = parse("""
                {"resourceType":"Patient","id":"2518","name":[
                  {"use":"usual","family":"Test","given":["Daffy","Tiffany"]}]}
                """);

        PatientColumns columns = columnsFor(patient);

        assertThat(columns.get(EcdsColumn.MemberLastName)).isEqualTo("Test");
        assertThat(columns.get(EcdsColumn.MemberFirstName)).isEqualTo("Daffy");
    }

    @Test
    void firstNameTakesOnlyTheLeadingGivenName() {
        JsonNode patient = parse("""
                {"resourceType":"Patient","id":"1","name":[{"family":"X","given":["Daffy","Tiffany"]}]}
                """);

        assertThat(columnsFor(patient).get(EcdsColumn.MemberFirstName)).isEqualTo("Daffy");
    }

    @Test
    void stripsEmbeddedTabsFromNames() {
        JsonNode patient = parse("""
                {"resourceType":"Patient","id":"1","name":[{"family":"Te\\tst","given":["Da\\tffy"]}]}
                """);

        PatientColumns columns = columnsFor(patient);

        assertThat(columns.get(EcdsColumn.MemberLastName)).isEqualTo("Test");
        assertThat(columns.get(EcdsColumn.MemberFirstName)).isEqualTo("Daffy");
    }

    // --- DOB / gender ------------------------------------------------------

    @Test
    void birthDateIsEmittedVerbatim() {
        JsonNode patient = parse("{\"resourceType\":\"Patient\",\"id\":\"1\",\"birthDate\":\"2014-02-01\"}");

        assertThat(columnsFor(patient).get(EcdsColumn.MemberDOB)).isEqualTo("2014-02-01");
    }

    @Test
    void aPartialBirthDateIsNotEmitted() {
        // varchar(10) cannot be satisfied by YYYY or YYYY-MM.
        JsonNode patient = parse("{\"resourceType\":\"Patient\",\"id\":\"1\",\"birthDate\":\"2014-02\"}");

        assertThat(columnsFor(patient).get(EcdsColumn.MemberDOB)).isEmpty();
    }

    @Test
    void genderIsMappedToTheSingleLetterCode() {
        assertThat(genderOf("female")).isEqualTo("F");
        assertThat(genderOf("male")).isEqualTo("M");
        assertThat(genderOf("other")).isEqualTo("O");
        assertThat(genderOf("unknown")).isEqualTo("U");
    }

    @Test
    void aNullFlavouredBirthsexFallsBackToAdministrativeGender() {
        // Patient/2518: us-core-birthsex is UNK while gender is female. A null flavour is
        // semantically absent, so the known administrative gender wins.
        JsonNode patient = parse("""
                {"resourceType":"Patient","id":"2518","gender":"female",
                 "extension":[{"url":"%s","valueCode":"UNK"}]}
                """.formatted(BIRTHSEX));

        assertThat(columnsFor(patient).get(EcdsColumn.MemberGender)).isEqualTo("F");
    }

    @Test
    void arealBirthsexWinsOverAdministrativeGender() {
        JsonNode patient = parse("""
                {"resourceType":"Patient","id":"1","gender":"female",
                 "extension":[{"url":"%s","valueCode":"M"}]}
                """.formatted(BIRTHSEX));

        assertThat(columnsFor(patient).get(EcdsColumn.MemberGender)).isEqualTo("M");
    }

    // --- Sexparameterforclinicaluse ---------------------------------------

    @Test
    void sexParameterIsDerivedFromGenderWhenTheExtensionIsAbsent() {
        JsonNode patient = parse("{\"resourceType\":\"Patient\",\"id\":\"1\",\"gender\":\"female\"}");

        assertThat(columnsFor(patient).get(EcdsColumn.Sexparameterforclinicaluse))
                .isEqualTo("female-typical");
    }

    @Test
    void aNativeSexParameterWinsOverTheDerivation() {
        JsonNode patient = parse("""
                {"resourceType":"Patient","id":"1","gender":"female",
                 "extension":[{"url":"http://hl7.org/fhir/StructureDefinition/patient-sexParameterForClinicalUse",
                               "valueCode":"specified"}]}
                """);

        assertThat(columnsFor(patient).get(EcdsColumn.Sexparameterforclinicaluse))
                .isEqualTo("specified");
    }

    @Test
    void sexParameterCoversEveryDerivedGender() {
        assertThat(sexParameterOf("male")).isEqualTo("male-typical");
        assertThat(sexParameterOf("other")).isEqualTo("specified");
        assertThat(sexParameterOf("unknown")).isEqualTo("unknown");
    }

    // --- application -------------------------------------------------------

    @Test
    void columnsAreStampedOntoARow() {
        JsonNode patient = parse("""
                {"resourceType":"Patient","id":"2518","gender":"female","birthDate":"2014-02-01",
                 "identifier":[{"system":"%shorizon-id","value":"11111"}],
                 "name":[{"use":"usual","family":"Test","given":["Daffy"]}]}
                """.formatted(HORIZON));
        EcdsRow row = new EcdsRow();

        columnsFor(patient).applyTo(row);

        assertThat(row.get(EcdsColumn.MemberKey)).isEqualTo("11111");
        assertThat(row.get(EcdsColumn.MemberLastName)).isEqualTo("Test");
        assertThat(row.get(EcdsColumn.MemberDOB)).isEqualTo("2014-02-01");
        assertThat(row.get(EcdsColumn.MemberGender)).isEqualTo("F");
    }

    @Test
    void anUnknownPatientYieldsEmptyColumns() {
        PatientColumns columns = PatientColumns.absent();

        assertThat(columns.get(EcdsColumn.MemberKey)).isEmpty();
        assertThat(columns.get(EcdsColumn.MemberGender)).isEmpty();
    }

    private String genderOf(String theGender) {
        return columnsFor(parse("{\"resourceType\":\"Patient\",\"id\":\"1\",\"gender\":\"" + theGender + "\"}"))
                .get(EcdsColumn.MemberGender);
    }

    private String sexParameterOf(String theGender) {
        return columnsFor(parse("{\"resourceType\":\"Patient\",\"id\":\"1\",\"gender\":\"" + theGender + "\"}"))
                .get(EcdsColumn.Sexparameterforclinicaluse);
    }

    private PatientColumns columnsFor(JsonNode thePatient) {
        return PatientColumns.from(thePatient);
    }

    private JsonNode parse(String theJson) {
        try {
            return myMapper.readTree(theJson);
        } catch (Exception e) {
            throw new IllegalArgumentException(theJson, e);
        }
    }
}
