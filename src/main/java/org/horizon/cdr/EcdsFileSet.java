package org.horizon.cdr;

import ca.uhn.fhir.rest.api.server.bulk.ConvertedFile;
import ca.uhn.fhir.rest.api.server.bulk.ConvertedFiles;
import org.horizon.ecds.EcdsCsv;
import org.horizon.ecds.EcdsRow;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The CSV files one converter call produces for one label, split by size.
 *
 * <p>Horizon caps a file at 5 GB and every file here is also a Binary row in the database, so
 * a much lower cap is applied: once a file would exceed it, it is closed and the next row starts
 * a new file with its own header. Rows are independent, so any row boundary is a valid split.
 *
 * <p>A set always yields at least one file, header-only if no row was written — the export
 * step rejects a converter that returns nothing.
 */
final class EcdsFileSet {

    private static final byte[] HEADER = (EcdsCsv.headerLine() + EcdsCsv.RECORD_SEPARATOR)
            .getBytes(StandardCharsets.UTF_8);

    private final String myLabel;
    private final String myMimeType;
    private final long myMaxBytes;
    private final List<ConvertedFile> myFiles = new ArrayList<>();
    private ByteArrayOutputStream myCurrent;
    private long myRows;

    EcdsFileSet(String theLabel, String theMimeType, long theMaxBytes) {
        myLabel = theLabel;
        myMimeType = theMimeType;
        myMaxBytes = theMaxBytes;
        startFile();
    }

    void write(EcdsRow theRow) {
        byte[] line = (EcdsCsv.line(theRow) + EcdsCsv.RECORD_SEPARATOR).getBytes(StandardCharsets.UTF_8);
        // A single row larger than the cap still has to go somewhere; it gets a file to itself.
        if (myCurrent.size() > HEADER.length && myCurrent.size() + line.length > myMaxBytes) {
            closeFile();
            startFile();
        }
        myCurrent.write(line, 0, line.length);
        myRows++;
    }

    long rows() {
        return myRows;
    }

    /** Closes the open file and adds every file to {@code theInto}. Call once. */
    void addTo(ConvertedFiles theInto) {
        closeFile();
        for (ConvertedFile file : myFiles) {
            theInto.addFile(file);
        }
    }

    int fileCount() {
        return myFiles.size() + (myCurrent == null ? 0 : 1);
    }

    private void startFile() {
        myCurrent = new ByteArrayOutputStream();
        myCurrent.write(HEADER, 0, HEADER.length);
    }

    private void closeFile() {
        if (myCurrent == null) {
            return;
        }
        ConvertedFile file = new ConvertedFile();
        file.setResourceType(myLabel);
        file.setMimeType(myMimeType);
        file.setBytes(myCurrent.toByteArray());
        myFiles.add(file);
        myCurrent = null;
    }
}
