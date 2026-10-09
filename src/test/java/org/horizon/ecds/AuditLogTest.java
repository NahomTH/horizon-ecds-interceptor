package org.horizon.ecds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLogTest {

    @TempDir
    Path myFolder;

    @Test
    void writesAHeaderEvenWhenNothingIsRecorded() throws IOException {
        Path output = myFolder.resolve("audit.csv");

        try (AuditLog log = new AuditLog(output)) {
            // nothing recorded
        }

        assertThat(Files.readAllLines(output))
                .containsExactly("severity,code,resourceType,resourceId,column,detail");
    }

    @Test
    void recordsAWarningAgainstItsSourceResource() throws IOException {
        Path output = myFolder.resolve("audit.csv");

        try (AuditLog log = new AuditLog(output)) {
            log.warn(AuditCode.PROVIDER_KEY_MISSING, "Observation/2689", EcdsColumn.ProviderKey,
                    "no NPI on the performer");
        }

        List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1))
                .isEqualTo("WARN,PROVIDER_KEY_MISSING,Observation,2689,ProviderKey,no NPI on the performer");
    }

    @Test
    void recordsAnError() throws IOException {
        Path output = myFolder.resolve("audit.csv");

        try (AuditLog log = new AuditLog(output)) {
            log.error(AuditCode.MEMBER_DOB_PARTIAL, "Patient/2518", EcdsColumn.MemberDOB,
                    "birthDate '2014-02' is not YYYY-MM-DD");
        }

        assertThat(Files.readAllLines(output).get(1)).startsWith("ERROR,MEMBER_DOB_PARTIAL,Patient,2518,");
    }

    @Test
    void countsEntriesByCode() throws IOException {
        try (AuditLog log = new AuditLog(myFolder.resolve("audit.csv"))) {
            log.warn(AuditCode.PROVIDER_KEY_MISSING, "Observation/1", EcdsColumn.ProviderKey, "");
            log.warn(AuditCode.PROVIDER_KEY_MISSING, "Observation/2", EcdsColumn.ProviderKey, "");
            log.warn(AuditCode.DOS_MISSING, "Condition/3", EcdsColumn.DOS, "");

            assertThat(log.counts())
                    .containsEntry(AuditCode.PROVIDER_KEY_MISSING, 2L)
                    .containsEntry(AuditCode.DOS_MISSING, 1L);
        }
    }

    @Test
    void knowsWhetherAnyErrorWasRecorded() throws IOException {
        try (AuditLog log = new AuditLog(myFolder.resolve("audit.csv"))) {
            log.warn(AuditCode.DOS_MISSING, "Condition/1", EcdsColumn.DOS, "");
            assertThat(log.hasErrors()).isFalse();

            log.error(AuditCode.MEMBER_DOB_PARTIAL, "Patient/1", EcdsColumn.MemberDOB, "");
            assertThat(log.hasErrors()).isTrue();
        }
    }

    @Test
    void quotesADetailContainingAComma() throws IOException {
        Path output = myFolder.resolve("audit.csv");

        try (AuditLog log = new AuditLog(output)) {
            log.warn(AuditCode.DOS_MISSING, "Condition/1", EcdsColumn.DOS, "no onset, no recordedDate");
        }

        assertThat(Files.readAllLines(output).get(1)).contains("\"no onset, no recordedDate\"");
    }

    @Test
    void handlesAResourceIdentifierWithNoSlash() throws IOException {
        Path output = myFolder.resolve("audit.csv");

        try (AuditLog log = new AuditLog(output)) {
            log.warn(AuditCode.UNRESOLVED_REFERENCE, "", null, "no subject");
        }

        assertThat(Files.readAllLines(output).get(1)).isEqualTo("WARN,UNRESOLVED_REFERENCE,,,,no subject");
    }
}
