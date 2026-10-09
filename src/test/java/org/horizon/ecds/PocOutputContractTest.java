package org.horizon.ecds;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the generated files to the real POC output, not to the spec.
 *
 * <p>{@link EcdsColumnTest} already checks the enum against {@code notes/ECDS mapping.csv}.
 * This checks the other half: that the line this code actually <em>writes</em> is
 * byte-identical to the line the reference mapper wrote when it produced the 668-row
 * acceptance file. The enum test cannot catch a change in quoting, field order on the wire or
 * the record separator; this one can.
 *
 * <p>Only the header lines are vendored. The 668 data rows came from Horizon's server and the
 * source export is not redistributable, so an end-to-end comparison is not reproducible here.
 */
class PocOutputContractTest {

    @Test
    void theDataHeaderIsByteIdenticalToTheRealPocOutput() throws IOException {
        assertThat(EcdsCsv.headerLine()).isEqualTo(resource("poc-output-header.csv"));
    }

    @Test
    void theAuditHeaderIsByteIdenticalToTheRealPocAudit() throws IOException {
        StringWriter written = new StringWriter();
        try (AuditLog audit = new AuditLog(written)) {
            // Nothing written: the header is printed on construction.
            assertThat(audit.total()).isZero();
        }

        assertThat(written.toString().lines().findFirst()).hasValue(resource("poc-audit-header.csv"));
    }

    @Test
    void everyAuditCodeTheRealRunEmittedStillExists() {
        // The reference run emitted these three. Renaming one would silently break whatever
        // greps the audit file, so they are named here rather than derived from the enum.
        Set<String> emittedByTheRealRun =
                new TreeSet<>(Set.of("DOS_FROM_ENCOUNTER", "PROVIDER_KEY_MISSING", "REQUIRED_FIELD_MISSING"));

        Set<String> declared = Arrays.stream(AuditCode.values())
                .map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(declared).containsAll(emittedByTheRealRun);
    }

    private static String resource(String theName) throws IOException {
        try (InputStream in = PocOutputContractTest.class.getResourceAsStream("/oracle/" + theName)) {
            assertThat(in).as(theName).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }
}
