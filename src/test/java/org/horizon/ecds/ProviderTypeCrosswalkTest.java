package org.horizon.ecds;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderTypeCrosswalkTest {

    /** The human-facing source of truth. Not on the packaged classpath, so tests only. */
    private static final Path SPEC_CROSSWALK =
            Path.of("notes", "ECDS-mapping-summary-crosswalk-table-provider-type.csv");

    /** The appendix in the mapping spec. */
    private static final Set<String> VALID_PROVIDER_TYPES = Set.of(
            "CARD", "DN", "ENDO", "GAST", "GYN", "INFD", "MHN", "MHP", "NEPH",
            "NPCP", "OB", "OTHR", "PA", "PCP", "PNC", "RN", "RPH", "VC");

    @Test
    void looksUpAKnownTaxonomyCode() {
        assertThat(ProviderTypeCrosswalk.providerTypeFor("207RA0001X")).contains("CARD");
        assertThat(ProviderTypeCrosswalk.providerTypeFor("207Q00000X")).contains("PCP");
        assertThat(ProviderTypeCrosswalk.providerTypeFor("183500000X")).contains("RPH");
    }

    @Test
    void isEmptyForACodeNotInTheCrosswalk() {
        assertThat(ProviderTypeCrosswalk.providerTypeFor("999X00000X")).isEmpty();
    }

    @Test
    void isEmptyForNullOrBlank() {
        assertThat(ProviderTypeCrosswalk.providerTypeFor(null)).isEmpty();
        assertThat(ProviderTypeCrosswalk.providerTypeFor("   ")).isEmpty();
    }

    @Test
    void lookupIsCaseInsensitiveAndTrimmed() {
        assertThat(ProviderTypeCrosswalk.providerTypeFor(" 207ra0001x ")).contains("CARD");
    }

    @Test
    void everyProviderTypeIsOneOfTheEighteenValidValues() {
        assertThat(ProviderTypeCrosswalk.allProviderTypes()).isSubsetOf(VALID_PROVIDER_TYPES);
    }

    /**
     * The drift guard. The shipped resource is derived from the spec crosswalk, and two copies
     * can diverge, so this fails the build if they ever do — the same technique that protects
     * the 58-column order in {@link EcdsColumnTest}.
     */
    @Test
    void carriesEveryMappingFromTheSpecCrosswalk() throws IOException {
        Map<String, String> fromSpec = readSpecCrosswalk();

        assertThat(fromSpec).isNotEmpty();
        assertThat(ProviderTypeCrosswalk.size()).isEqualTo(fromSpec.size());
        fromSpec.forEach((nuccCode, providerType) ->
                assertThat(ProviderTypeCrosswalk.providerTypeFor(nuccCode))
                        .as(nuccCode)
                        .contains(providerType));
    }

    private Map<String, String> readSpecCrosswalk() throws IOException {
        Map<String, String> mappings = new HashMap<>();
        try (Reader reader = Files.newBufferedReader(SPEC_CROSSWALK, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .get()
                     .parse(reader)) {
            for (CSVRecord record : parser) {
                String nuccCode = record.get("NUCC Taxonomy Code").trim();
                String providerType = record.get("ProviderType1 (Smile proposal)").trim();
                if (!nuccCode.isEmpty() && !providerType.isEmpty()) {
                    mappings.put(nuccCode, providerType);
                }
            }
        }
        return mappings;
    }
}
