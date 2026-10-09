package org.horizon.ecds;

import com.fasterxml.jackson.databind.JsonNode;
import org.horizon.fhir.FhirPaths;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The seven member columns, derived once per Patient and stamped onto every row for that member.
 */
public class PatientColumns {

    private static final String HORIZON_IDENTIFIER_PREFIX =
            "http://horizonblue.com/fhir/patient/identifier/";

    /** Horizon's member key. */
    private static final String MEMBER_KEY_SYSTEM = HORIZON_IDENTIFIER_PREFIX + "horizon-id";

    /** Subscriber-facing ids, in the order the spec prefers them for MemberID. */
    private static final List<String> MEMBER_ID_SYSTEMS = List.of(
            HORIZON_IDENTIFIER_PREFIX + "card-id",
            HORIZON_IDENTIFIER_PREFIX + "medicaid-id");

    private static final String BIRTHSEX_URL =
            "http://hl7.org/fhir/us/core/StructureDefinition/us-core-birthsex";
    private static final String SEX_PARAMETER_URL =
            "http://hl7.org/fhir/StructureDefinition/patient-sexParameterForClinicalUse";

    /** MemberKey is capped by the Horizon layout. */
    private static final int MEMBER_KEY_MAX_LENGTH = 30;

    private static final Pattern FULL_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /**
     * Null flavours. A birthsex of UNK carries no more information than an absent one, so it is
     * treated as absent and administrative gender is used instead.
     */
    private static final List<String> NULL_FLAVOURS = List.of("UNK", "ASKU", "OTH", "NI", "NAV");

    private final Map<EcdsColumn, String> myValues;

    private PatientColumns(Map<EcdsColumn, String> theValues) {
        myValues = theValues;
    }

    /** Empty columns, for a row whose Patient reference could not be resolved. */
    public static PatientColumns absent() {
        return new PatientColumns(new EnumMap<>(EcdsColumn.class));
    }

    public static PatientColumns from(JsonNode thePatient) {
        Map<EcdsColumn, String> values = new EnumMap<>(EcdsColumn.class);
        if (thePatient == null) {
            return new PatientColumns(values);
        }

        String memberKey = identifierBySystem(thePatient, MEMBER_KEY_SYSTEM)
                .or(() -> anyIdentifier(thePatient))
                .map(PatientColumns::truncateMemberKey)
                .orElse("");
        values.put(EcdsColumn.MemberKey, memberKey);
        values.put(EcdsColumn.MemberID, memberId(thePatient, memberKey));

        JsonNode name = preferredName(thePatient);
        values.put(EcdsColumn.MemberLastName, clean(FhirPaths.primitive(name, "family").orElse("")));
        values.put(EcdsColumn.MemberFirstName, clean(firstGivenName(name)));

        values.put(EcdsColumn.MemberDOB, fullBirthDate(thePatient));

        String gender = memberGender(thePatient);
        values.put(EcdsColumn.MemberGender, gender);
        values.put(EcdsColumn.Sexparameterforclinicaluse, sexParameter(thePatient, gender));

        return new PatientColumns(values);
    }

    public String get(EcdsColumn theColumn) {
        return myValues.getOrDefault(theColumn, "");
    }

    public void applyTo(EcdsRow theRow) {
        myValues.forEach(theRow::set);
    }

    /**
     * MemberID must be a different value from MemberKey — Horizon's layout says so explicitly,
     * because the two are crosswalked against each other on ingest.
     */
    private static String memberId(JsonNode thePatient, String theMemberKey) {
        Optional<String> typed = identifierByTypeCode(thePatient, "MB");
        if (typed.isPresent() && !typed.get().equals(theMemberKey)) {
            return typed.get();
        }
        for (String system : MEMBER_ID_SYSTEMS) {
            Optional<String> value = identifierBySystem(thePatient, system);
            if (value.isPresent() && !value.get().equals(theMemberKey)) {
                return value.get();
            }
        }
        return "";
    }

    private static Optional<String> identifierBySystem(JsonNode thePatient, String theSystem) {
        return FhirPaths.array(thePatient, "identifier")
                .filter(identifier -> FhirPaths.primitive(identifier, "system")
                        .filter(theSystem::equals)
                        .isPresent())
                .map(identifier -> FhirPaths.primitive(identifier, "value").orElse(""))
                .filter(value -> !value.isEmpty())
                .findFirst();
    }

    private static Optional<String> identifierByTypeCode(JsonNode thePatient, String theCode) {
        return FhirPaths.array(thePatient, "identifier")
                .filter(identifier -> FhirPaths.array(identifier.path("type"), "coding")
                        .anyMatch(coding -> FhirPaths.primitive(coding, "code")
                                .filter(theCode::equals)
                                .isPresent()))
                .map(identifier -> FhirPaths.primitive(identifier, "value").orElse(""))
                .filter(value -> !value.isEmpty())
                .findFirst();
    }

    private static Optional<String> anyIdentifier(JsonNode thePatient) {
        return FhirPaths.array(thePatient, "identifier")
                .map(identifier -> FhirPaths.primitive(identifier, "value").orElse(""))
                .filter(value -> !value.isEmpty())
                .findFirst();
    }

    private static String truncateMemberKey(String theValue) {
        return theValue.length() > MEMBER_KEY_MAX_LENGTH
                ? theValue.substring(0, MEMBER_KEY_MAX_LENGTH)
                : theValue;
    }

    /** The official name where there is one; otherwise the first entry. */
    private static JsonNode preferredName(JsonNode thePatient) {
        return FhirPaths.array(thePatient, "name")
                .filter(name -> FhirPaths.primitive(name, "use").filter("official"::equals).isPresent())
                .findFirst()
                .orElseGet(() -> FhirPaths.array(thePatient, "name").findFirst().orElse(null));
    }

    private static String firstGivenName(JsonNode theName) {
        if (theName == null) {
            return "";
        }
        return FhirPaths.array(theName, "given")
                .map(JsonNode::asText)
                .filter(given -> !given.isBlank())
                .findFirst()
                .orElse("");
    }

    /** The output is tab-delimited downstream, so embedded delimiters are stripped. */
    private static String clean(String theValue) {
        return theValue.replaceAll("[\\t\\r\\n]", "").strip();
    }

    /** A partial date (YYYY or YYYY-MM) cannot satisfy the varchar(10) column. */
    private static String fullBirthDate(JsonNode thePatient) {
        return FhirPaths.primitive(thePatient, "birthDate")
                .filter(date -> FULL_DATE.matcher(date).matches())
                .orElse("");
    }

    private static String memberGender(JsonNode thePatient) {
        Optional<String> birthsex = FhirPaths.extensionValue(thePatient, BIRTHSEX_URL)
                .filter(value -> !isNullFlavour(value));
        if (birthsex.isPresent()) {
            return birthsex.get();
        }
        return switch (FhirPaths.primitive(thePatient, "gender").orElse("")) {
            case "male" -> "M";
            case "female" -> "F";
            case "other" -> "O";
            case "unknown" -> "U";
            default -> "";
        };
    }

    /** A native value always wins; the derivation is only a fallback. */
    private static String sexParameter(JsonNode thePatient, String theMemberGender) {
        Optional<String> native_ = FhirPaths.extensionValue(thePatient, SEX_PARAMETER_URL);
        if (native_.isPresent()) {
            return native_.get();
        }
        return switch (theMemberGender) {
            case "F" -> "female-typical";
            case "M" -> "male-typical";
            case "O" -> "specified";
            case "U" -> "unknown";
            default -> "";
        };
    }

    private static boolean isNullFlavour(String theValue) {
        return NULL_FLAVOURS.contains(theValue.toUpperCase(java.util.Locale.ROOT));
    }
}
