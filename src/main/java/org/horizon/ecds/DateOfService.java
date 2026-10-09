package org.horizon.ecds;

import com.fasterxml.jackson.databind.JsonNode;
import org.horizon.fhir.FhirPaths;
import org.horizon.fhir.ResourceResolver;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Derives {@code DOS} and {@code DOSThru}.
 *
 * <p>Each resource type has its own precedence of date elements, given by the mapping doc; the
 * first populated one wins. Values are normalised to a {@code YYYY-MM-DD} local date in
 * {@code America/New_York}, per the Horizon Quality Reporting rule.
 */
public final class DateOfService {

    /** Horizon reports in Eastern time. */
    private static final ZoneId REPORTING_ZONE = ZoneId.of("America/New_York");

    private static final Pattern DATE_ONLY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** First populated element wins, per the mapping doc's precedence for each type. */
    private static final Map<String, List<String>> START_PRECEDENCE = Map.of(
            "Observation", List.of("effectiveDateTime", "effectivePeriod.start", "issued"),
            "Procedure", List.of("performedDateTime", "performedPeriod.start"),
            "Encounter", List.of("period.start"),
            "Condition", List.of("onsetDateTime", "recordedDate"),
            "Immunization", List.of("occurrenceDateTime"),
            "MedicationRequest", List.of("authoredOn"),
            "MedicationDispense", List.of("whenHandedOver", "whenPrepared"),
            // effectivePeriod.start mirrors the Observation entry the spec gives. Every
            // DiagnosticReport in the export carries it and no effectiveDateTime, so
            // omitting it discarded a date that was present.
            "DiagnosticReport", List.of("effectiveDateTime", "effectivePeriod.start", "issued"));

    /** A genuine period end. Anything else leaves DOSThru equal to DOS. */
    private static final Map<String, List<String>> END_PRECEDENCE = Map.of(
            "Observation", List.of("effectivePeriod.end"),
            "Procedure", List.of("performedPeriod.end"),
            "Encounter", List.of("period.end"),
            "Condition", List.of("abatementDateTime"));

    private DateOfService() {
    }

    /** The service date from the resource's own elements, or empty. */
    public static String of(JsonNode theResource) {
        return firstPopulated(theResource, START_PRECEDENCE);
    }

    /**
     * The service date, falling back to the encounter the record belongs to.
     *
     * <p>Many records in a CCD-derived export carry no date of their own — most Conditions
     * here hold only a code and an encounter — and for those the encounter is the only date
     * that exists.
     *
     * <p>The fallback is one-way: a date the record states always wins. Where records carry
     * both, the two agree only about half the time, and onset dates in particular run years
     * ahead of the visit that recorded them. Inheriting over a stated date would silently
     * replace a clinical fact with an administrative one.
     */
    public static String of(JsonNode theResource, ResourceResolver theIndex) {
        String own = of(theResource);
        if (!own.isEmpty()) {
            return own;
        }
        String inherited = encounterDate(theResource, theIndex, "period.start");
        // A record can state when something ended without saying when it began. Inheriting an
        // encounter that postdates that end would close the row before it opened, so the
        // record's own end is the better start.
        String statedEnd = firstPopulated(theResource, END_PRECEDENCE);
        if (!inherited.isEmpty() && !statedEnd.isEmpty() && statedEnd.compareTo(inherited) < 0) {
            return statedEnd;
        }
        return inherited;
    }

    /**
     * The service end date. Equal to {@code theDos} unless the source carries a real period end;
     * no sentinel date is ever written.
     */
    public static String throughOf(JsonNode theResource, String theDos) {
        String end = firstPopulated(theResource, END_PRECEDENCE);
        return end.isEmpty() ? theDos : end;
    }

    /**
     * The service end date, falling back to the encounter's period end.
     *
     * <p>A real {@code abatementDateTime} or {@code effectivePeriod.end} on the record always
     * wins. The encounter's end is used only when the start also came from that encounter:
     * pairing a record's own start with the encounter's end invents a span neither source
     * states, and in this export that produced a nine-year one.
     */
    public static String throughOf(JsonNode theResource, String theDos, ResourceResolver theIndex) {
        String end = firstPopulated(theResource, END_PRECEDENCE);
        if (!end.isEmpty()) {
            return end;
        }
        if (!of(theResource).isEmpty()) {
            return theDos;
        }
        String encounterEnd = encounterDate(theResource, theIndex, "period.end");
        return encounterEnd.isEmpty() ? theDos : encounterEnd;
    }

    /** A date from the encounter this record belongs to, or empty when there is none. */
    private static String encounterDate(JsonNode theResource, ResourceResolver theIndex, String thePath) {
        if (theResource == null || theIndex == null) {
            return "";
        }
        return FhirPaths.reference(theResource.get("encounter"))
                .flatMap(reference -> theIndex.resolve(reference, theResource))
                .flatMap(encounter -> valueAt(encounter, thePath))
                .map(DateOfService::toReportingDate)
                .filter(date -> !date.isEmpty())
                .orElse("");
    }

    private static String firstPopulated(JsonNode theResource, Map<String, List<String>> thePrecedence) {
        if (theResource == null) {
            return "";
        }
        String resourceType = theResource.path("resourceType").asText();
        for (String path : thePrecedence.getOrDefault(resourceType, List.of())) {
            Optional<String> raw = valueAt(theResource, path);
            if (raw.isPresent()) {
                String normalised = toReportingDate(raw.get());
                if (!normalised.isEmpty()) {
                    return normalised;
                }
            }
        }
        return "";
    }

    /** Reads either {@code field} or {@code field.child}, honouring data-absent elements. */
    private static Optional<String> valueAt(JsonNode theResource, String thePath) {
        int dot = thePath.indexOf('.');
        if (dot < 0) {
            return FhirPaths.primitive(theResource, thePath);
        }
        JsonNode parent = theResource.get(thePath.substring(0, dot));
        if (FhirPaths.isAbsent(parent)) {
            return Optional.empty();
        }
        return FhirPaths.primitive(parent, thePath.substring(dot + 1));
    }

    /**
     * Normalises a FHIR date or dateTime to {@code YYYY-MM-DD} in the reporting zone.
     *
     * <p>A date-only value carries no time or zone, so it is returned as-is: converting it could
     * shift it by a day. Only a dateTime is moved into the reporting zone before truncating.
     */
    private static String toReportingDate(String theValue) {
        String value = theValue.trim();
        if (DATE_ONLY.matcher(value).matches()) {
            return value;
        }
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(REPORTING_ZONE).toLocalDate().toString();
        } catch (DateTimeParseException e) {
            // No offset: FHIR allows it, and there is nothing to convert, so read it as local.
        }
        try {
            return LocalDateTime.parse(value).toLocalDate().toString();
        } catch (DateTimeParseException e) {
            // Not a full dateTime either.
        }
        try {
            // A partial date (YYYY or YYYY-MM) cannot satisfy the YYYY-MM-DD column.
            return LocalDate.parse(value).toString();
        } catch (DateTimeParseException e) {
            return "";
        }
    }
}
