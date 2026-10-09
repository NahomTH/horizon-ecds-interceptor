package org.horizon.ecds;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Translates a NUCC provider-taxonomy code into a Horizon ProviderType.
 *
 * <p>The mapping is Horizon's, delivered as a spreadsheet and kept in
 * {@code notes/ECDS-mapping-summary-crosswalk-table-provider-type.csv}. That file is the
 * human-facing source of truth but is not on the packaged classpath, so a trimmed two-column
 * copy ships as a resource instead. {@code ProviderTypeCrosswalkTest} parses both and fails the
 * build if they ever diverge.
 */
public final class ProviderTypeCrosswalk {

    private static final String RESOURCE = "/provider-type-crosswalk.csv";

    /** Loaded once; the file is small enough that eager loading costs nothing. */
    private static final Map<String, String> PROVIDER_TYPES_BY_NUCC_CODE = load();

    private ProviderTypeCrosswalk() {
    }

    /**
     * The Horizon ProviderType for a NUCC taxonomy code.
     *
     * @return empty when the code is absent from the crosswalk, which means the crosswalk does
     *         not cover it — deliberately not conflated with {@code OTHR}, the crosswalk's own
     *         catch-all for codes it does cover
     */
    public static Optional<String> providerTypeFor(String theNuccCode) {
        if (theNuccCode == null || theNuccCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                PROVIDER_TYPES_BY_NUCC_CODE.get(normalise(theNuccCode)));
    }

    /** The distinct ProviderType values the crosswalk can produce. */
    public static Set<String> allProviderTypes() {
        return new LinkedHashSet<>(PROVIDER_TYPES_BY_NUCC_CODE.values());
    }

    public static int size() {
        return PROVIDER_TYPES_BY_NUCC_CODE.size();
    }

    private static String normalise(String theNuccCode) {
        return theNuccCode.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * A missing or unreadable crosswalk is fatal rather than silently blanking six columns:
     * an empty ProviderType is indistinguishable from a provider with no taxonomy.
     */
    private static Map<String, String> load() {
        Map<String, String> mappings = new HashMap<>();
        try (InputStream stream = ProviderTypeCrosswalk.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Crosswalk resource not found: " + RESOURCE);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Comments carry the provenance note; the header names the two columns.
                    if (line.isBlank() || line.startsWith("#") || line.startsWith("nuccCode")) {
                        continue;
                    }
                    int comma = line.indexOf(',');
                    if (comma > 0) {
                        mappings.put(normalise(line.substring(0, comma)),
                                line.substring(comma + 1).trim());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + RESOURCE, e);
        }
        return Collections.unmodifiableMap(mappings);
    }
}
