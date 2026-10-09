package org.horizon.cdr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EcdsConverterConfigTest {

    private static final String[] SETTINGS = {
            "ECDS_OUTPUT_FORMAT", "ECDS_FALLBACK_FEED", "ECDS_CACHE_SIZE",
            "ECDS_AUDIT_TYPE", "ECDS_MAX_FILE_BYTES", "ECDS_PAGE_SIZE"};

    @AfterEach
    void clearProperties() {
        for (String name : SETTINGS) {
            System.clearProperty(name);
        }
    }

    @Test
    void anEmptyAuditTypeTurnsTheAuditFileOff() {
        // The documented way to disable it. Treating blank as "unset" put EcdsAudit back and
        // left the audit on, so the setting could not be used at all.
        System.setProperty("ECDS_AUDIT_TYPE", "");

        assertThat(EcdsConverterConfig.fromEnvironment().writesAudit()).isFalse();
    }

    @Test
    void anUnsetAuditTypeKeepsTheDefault() {
        assertThat(EcdsConverterConfig.fromEnvironment().auditType())
                .isEqualTo(EcdsConverterConfig.DEFAULT_AUDIT_TYPE);
    }

    @Test
    void aBlankSettingWithNoMeaningfulEmptyValueFallsBackToTheDefault() {
        System.setProperty("ECDS_OUTPUT_FORMAT", "  ");
        System.setProperty("ECDS_FALLBACK_FEED", "");

        EcdsConverterConfig config = EcdsConverterConfig.fromEnvironment();

        assertThat(config.outputFormat()).isEqualTo(EcdsConverterConfig.DEFAULT_OUTPUT_FORMAT);
        assertThat(config.fallbackFeed()).isEqualTo("athena");
    }

    @Test
    void anUnreadableNumberFallsBackInsteadOfFailingTheInterceptor() {
        // This runs inside the no-argument constructor Smile calls; a typo must not stop the
        // bean from being created, or $export comes back "Unsupported output format".
        System.setProperty("ECDS_CACHE_SIZE", "fifty thousand");
        System.setProperty("ECDS_PAGE_SIZE", "");

        EcdsConverterConfig config = EcdsConverterConfig.fromEnvironment();

        assertThat(config.cacheSize()).isEqualTo(EcdsConverterConfig.DEFAULT_CACHE_SIZE);
        assertThat(config.pageSize()).isEqualTo(EcdsConverterConfig.DEFAULT_PAGE_SIZE);
    }

    @Test
    void anOversizedFileCapIsClampedRatherThanTrusted() {
        // Horizon's limit is 5 GB; a byte[] cannot hold it and the stream doubles on the way.
        System.setProperty("ECDS_MAX_FILE_BYTES", Long.toString(5L * 1024 * 1024 * 1024));

        assertThat(EcdsConverterConfig.fromEnvironment().maxFileBytes())
                .isEqualTo(EcdsConverterConfig.MAX_SAFE_FILE_BYTES);
    }

    @Test
    void aMisspelledFeedIsFatal() {
        // Deliberately not tolerated: it would label every row with another feed's constants.
        System.setProperty("ECDS_FALLBACK_FEED", "konzaa");

        assertThatThrownBy(EcdsConverterConfig::fromEnvironment)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFormatMatchesCaseInsensitivelyAndIgnoresParameters() {
        EcdsConverterConfig config = EcdsConverterConfig.defaults();

        assertThat(config.matches("text/csv")).isTrue();
        assertThat(config.matches("TEXT/CSV; charset=utf-8")).isTrue();
        assertThat(config.matches("application/fhir+ndjson")).isFalse();
        assertThat(config.matches(null)).isFalse();
    }
}
