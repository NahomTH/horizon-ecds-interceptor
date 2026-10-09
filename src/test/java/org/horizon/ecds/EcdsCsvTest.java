package org.horizon.ecds;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The line format the POC's writer produced, now shared with the bulk-export converter. */
class EcdsCsvTest {

    @Test
    void writesTheFiftyEightColumnHeader() {
        String[] names = EcdsCsv.headerLine().split(",");

        assertThat(names).hasSize(58);
        assertThat(names[0]).isEqualTo("MemberKey");
        assertThat(names[57]).isEqualTo("MemberGender");
        assertThat(EcdsCsv.headerLine()).doesNotContain("\r").doesNotContain("\n");
    }

    @Test
    void writesARowInColumnOrder() {
        EcdsRow row = new EcdsRow()
                .set(EcdsColumn.MemberKey, "11111")
                .set(EcdsColumn.MemberGender, "F");

        String[] values = EcdsCsv.line(row).split(",", -1);

        assertThat(values).hasSize(58);
        assertThat(values[0]).isEqualTo("11111");
        assertThat(values[57]).isEqualTo("F");
    }

    @Test
    void unsetColumnsAreWrittenAsEmptyFields() {
        String line = EcdsCsv.line(new EcdsRow().set(EcdsColumn.DOS, "2026-01-02"));

        assertThat(line.split(",", -1)).hasSize(58);
        assertThat(line).startsWith(",,,2026-01-02,");
    }

    @Test
    void quotesAValueContainingTheDelimiter() {
        String line = EcdsCsv.line(new EcdsRow().set(EcdsColumn.MemberLastName, "Smith, Jr"));

        assertThat(line).contains("\"Smith, Jr\"");
    }

    @Test
    void anEmptyLeadingFieldIsNotQuoted() {
        // commons-csv would write "" for a leading empty field; the writer stripped that and so
        // does this, otherwise column 1 would be the only column rendered differently when empty.
        String line = EcdsCsv.line(new EcdsRow().set(EcdsColumn.ProviderKey, "1234567890"));

        assertThat(line).startsWith(",1234567890,");
    }

    @Test
    void escapesAnEmbeddedQuote() {
        String line = EcdsCsv.line(new EcdsRow().set(EcdsColumn.MemberFirstName, "O\"Neil"));

        assertThat(line).contains("\"O\"\"Neil\"");
    }
}
