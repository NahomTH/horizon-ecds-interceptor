package org.horizon.ecds;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.QuoteMode;

import java.util.List;

/**
 * The one place the ECDS CSV line format is defined, shared by the file writer (CLI) and the
 * bulk-export converter (interceptor) so both produce byte-identical lines.
 *
 * <p>RFC-4180 quoting via commons-csv, minimal quote mode, {@code \r\n} record separator. One
 * artifact of commons-csv is removed: it always quotes a leading empty field so a record
 * starting with one cannot be read back as a blank line, which would leave column 1 the only
 * column rendered {@code ""} when empty.
 */
public final class EcdsCsv {

    public static final String RECORD_SEPARATOR = "\r\n";

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setQuoteMode(QuoteMode.MINIMAL)
            .get();

    private EcdsCsv() {
    }

    /** The header line, without a record separator. */
    public static String headerLine() {
        return line(EcdsColumn.header());
    }

    /** One row, without a record separator. */
    public static String line(EcdsRow theRow) {
        return line(theRow.toValues());
    }

    static String line(List<String> theValues) {
        String record = FORMAT.format(theValues.toArray(new String[0]));
        if (!theValues.isEmpty() && theValues.get(0).isEmpty() && record.startsWith("\"\"")) {
            return record.substring(2);
        }
        return record;
    }
}
