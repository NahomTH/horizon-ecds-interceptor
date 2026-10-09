package org.horizon.ecds;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Records why rows are incomplete, alongside the main output.
 *
 * <p>One file with a severity column rather than one file per issue, so a caller does not have
 * to know the list of things that can go wrong. {@code WARN} means the row ships with a blank
 * column; {@code ERROR} means it is unusable downstream.
 *
 * <p>{@code resourceId} matches {@code Ecdsaltid_00} in the output, so an audit line can be
 * joined back to the row it describes.
 */
public class AuditLog implements AutoCloseable {

    private static final String[] HEADER =
            {"severity", "code", "resourceType", "resourceId", "column", "detail"};

    private final CSVPrinter myPrinter;
    private final Map<AuditCode, Long> myCounts = new EnumMap<>(AuditCode.class);
    private boolean myHasErrors;

    public AuditLog(Path theOutputFile) throws IOException {
        // normalize(), and skip an existing directory: on macOS /tmp is a symlink to
        // private/tmp, and createDirectories throws FileAlreadyExists on a symlink.
        Path parent = theOutputFile.toAbsolutePath().normalize().getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            Files.createDirectories(parent);
        }
        BufferedWriter writer = Files.newBufferedWriter(theOutputFile, StandardCharsets.UTF_8);
        myPrinter = CSVFormat.DEFAULT.builder().setHeader(HEADER).get().print(writer);
    }

    /**
     * An audit log written to any {@link Writer} — a string buffer, for the bulk-export
     * converter, which hands the audit back as bytes rather than a file.
     */
    public AuditLog(Writer theWriter) throws IOException {
        myPrinter = CSVFormat.DEFAULT.builder().setHeader(HEADER).get().print(theWriter);
    }

    private AuditLog() {
        myPrinter = null;
    }

    /** An audit log that records nothing, for tests and callers that do not want a file. */
    public static AuditLog discarding() {
        return new AuditLog();
    }

    /** The row ships, but a column is blank. */
    public void warn(AuditCode theCode, String theResource, EcdsColumn theColumn, String theDetail) {
        record("WARN", theCode, theResource, theColumn, theDetail);
    }

    /** The row is unusable downstream. */
    public void error(AuditCode theCode, String theResource, EcdsColumn theColumn, String theDetail) {
        myHasErrors = true;
        record("ERROR", theCode, theResource, theColumn, theDetail);
    }

    public Map<AuditCode, Long> counts() {
        return Map.copyOf(myCounts);
    }

    public boolean hasErrors() {
        return myHasErrors;
    }

    public long total() {
        return myCounts.values().stream().mapToLong(Long::longValue).sum();
    }

    private void record(String theSeverity, AuditCode theCode, String theResource,
                        EcdsColumn theColumn, String theDetail) {
        myCounts.merge(theCode, 1L, Long::sum);
        if (myPrinter == null) {
            return;
        }
        // Ecdsaltid_00 is "<Type>/<id>"; split so the file can be filtered by either.
        String resource = theResource == null ? "" : theResource;
        int slash = resource.indexOf('/');
        String type = slash < 0 ? "" : resource.substring(0, slash);
        String id = slash < 0 ? resource : resource.substring(slash + 1);
        try {
            myPrinter.printRecord(theSeverity, theCode.name(), type, id,
                    theColumn == null ? "" : theColumn.columnName(),
                    theDetail == null ? "" : theDetail);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write audit entry", e);
        }
    }

    @Override
    public void close() throws IOException {
        if (myPrinter != null) {
            myPrinter.close(true);
        }
    }
}
