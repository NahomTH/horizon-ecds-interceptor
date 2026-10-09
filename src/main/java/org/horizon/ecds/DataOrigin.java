package org.horizon.ecds;

import com.fasterxml.jackson.databind.JsonNode;
import org.horizon.fhir.FhirPaths;

import java.util.Locale;

import java.util.Optional;

/**
 * Reads the source feed from a resource's {@code meta.tag}.
 *
 * <p>Smile CDR stamps every resource with its origin, so a single export can mix feeds and each
 * row can carry the right {@code ClientSystemOfRecordID} / {@code Datasourcename} rather than
 * relying on one value for the whole run.
 */
public final class DataOrigin {

    public static final String TAG_SYSTEM = "http://horizonblue.com/fhir/meta/tag/data-origin";

    private static final String ATHENA = "AthenaHealth";
    private static final String KONZA = "KonzaHealth";

    private DataOrigin() {
    }

    /**
     * The feed constants implied by this resource's data-origin tag.
     *
     * @return null when the resource carries no recognised origin tag, so the caller can fall
     *         back to the feed given on the command line rather than guessing
     */
    public static FeedConstants of(JsonNode theResource) {
        // Matched case-insensitively: the export has written both KonzaHealth and
        // KONZAHealth, and a casing change must not silently relabel a whole feed.
        return originCode(theResource)
                .map(code -> code.toLowerCase(Locale.ROOT))
                .map(code -> {
                    if (ATHENA.equalsIgnoreCase(code)) {
                        return FeedConstants.forFeed("athena");
                    }
                    if (KONZA.equalsIgnoreCase(code)) {
                        return FeedConstants.forFeed("konza");
                    }
                    return null;
                })
                .orElse(null);
    }

    /** The raw tag code, for reporting which origins an export contained. */
    public static Optional<String> originCode(JsonNode theResource) {
        if (theResource == null) {
            return Optional.empty();
        }
        return FhirPaths.array(theResource.path("meta"), "tag")
                .filter(tag -> FhirPaths.primitive(tag, "system")
                        .filter(TAG_SYSTEM::equals)
                        .isPresent())
                .map(tag -> FhirPaths.primitive(tag, "code").orElse(""))
                .filter(code -> !code.isEmpty())
                .findFirst();
    }
}
