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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EcdsColumnTest {

    /** The spec. Quoted fields contain embedded newlines, so it needs a real CSV parser. */
    private static final Path MAPPING_SPEC = Path.of("notes", "ECDS mapping.csv");

    @Test
    void columnEnumMatchesTheMappingSpecInOrder() throws IOException {
        List<String> fromSpec = readColumnNamesFromSpec();

        List<String> fromEnum = new ArrayList<>();
        for (EcdsColumn column : EcdsColumn.values()) {
            fromEnum.add(column.columnName());
        }

        // Output order is the spec's row order; a drift in either is a build failure.
        assertThat(fromEnum).isEqualTo(fromSpec);
    }

    @Test
    void thereAreFiftyEightColumns() {
        assertThat(EcdsColumn.values()).hasSize(58);
    }

    @Test
    void theFirstAndLastColumnsAnchorTheOrder() {
        assertThat(EcdsColumn.values()[0]).isEqualTo(EcdsColumn.MemberKey);
        assertThat(EcdsColumn.values()[57]).isEqualTo(EcdsColumn.MemberGender);
    }

    @Test
    void headerIsTheColumnNamesInDeclarationOrder() {
        List<String> header = EcdsColumn.header();

        assertThat(header).hasSize(58);
        assertThat(header.get(0)).isEqualTo("MemberKey");
        assertThat(header.get(57)).isEqualTo("MemberGender");
    }

    @Test
    void enumConstantNamesMatchTheirColumnNames() {
        // The spec's casing is inconsistent (Sexparameterforclinicaluse, HOMEGROWN_MOD_00);
        // the enum mirrors it exactly so the reconciliation above is plain equality.
        for (EcdsColumn column : EcdsColumn.values()) {
            assertThat(column.columnName()).isEqualTo(column.name());
        }
    }

    private List<String> readColumnNamesFromSpec() throws IOException {
        List<String> names = new ArrayList<>();
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
                    names.add(name);
                }
            }
        }
        return names;
    }
}
