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
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequiredColumnsTest {

    private static final Path MAPPING_SPEC = Path.of("notes", "ECDS mapping.csv");

    @Test
    void mandatoryColumnsAreTheOnesTheSpecMarksRequired() {
        assertThat(RequiredColumns.mandatory())
                .contains(EcdsColumn.MemberKey, EcdsColumn.DOS, EcdsColumn.DOSThru,
                        EcdsColumn.ReferenceID, EcdsColumn.SuppSource, EcdsColumn.HCFAPOS_00,
                        EcdsColumn.RxProviderFlag, EcdsColumn.Ecdsaltid_01)
                .doesNotContain(EcdsColumn.ProviderType_00, EcdsColumn.LOINC, EcdsColumn.Measure);
    }

    @Test
    void columnsTheSpecMarksBlankAreNotMandatory() {
        // "Blank" means Horizon fills it downstream, so an empty value is correct.
        assertThat(RequiredColumns.mandatory())
                .doesNotContain(EcdsColumn.PCPFlag, EcdsColumn.Measure, EcdsColumn.Submeasure,
                        EcdsColumn.Ecdsaltid_02);
    }

    @Test
    void providerKeyIsNotTreatedAsUnconditionallyMandatory() {
        // Marked "Yes - see transform": the transform explicitly permits a blank plus an
        // audit entry when no NPI or TIN can be reached.
        assertThat(RequiredColumns.mandatory()).doesNotContain(EcdsColumn.ProviderKey);
    }

    /**
     * The drift guard. Requiredness lives in the spec, so this fails the build if the code and
     * {@code notes/ECDS mapping.csv} ever disagree.
     */
    @Test
    void everyColumnsRequirednessMatchesTheSpec() throws IOException {
        Map<EcdsColumn, String> fromSpec = readRequirednessFromSpec();

        assertThat(fromSpec).hasSize(EcdsColumn.values().length);
        fromSpec.forEach((column, required) ->
                assertThat(RequiredColumns.mandatory().contains(column))
                        .as("%s is marked '%s'", column, required)
                        .isEqualTo("Yes".equalsIgnoreCase(required)));
    }

    private Map<EcdsColumn, String> readRequirednessFromSpec() throws IOException {
        Map<EcdsColumn, String> requiredness = new EnumMap<>(EcdsColumn.class);
        try (Reader reader = Files.newBufferedReader(MAPPING_SPEC, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     // The spec carries a trailing empty header cell, which commons-csv
                     // rejects unless duplicate/blank names are allowed.
                     .setAllowMissingColumnNames(true)
                     .get()
                     .parse(reader)) {
            for (CSVRecord record : parser) {
                String name = record.get("Column name").trim();
                if (!name.isEmpty()) {
                    requiredness.put(EcdsColumn.valueOf(name), record.get("Required").trim());
                }
            }
        }
        return requiredness;
    }
}
