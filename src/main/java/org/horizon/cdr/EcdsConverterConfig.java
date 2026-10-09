package org.horizon.cdr;

import org.horizon.ecds.FeedConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * The few settings the bulk-export converter needs. Everything else the CLI took as arguments
 * (types, since, filters, output location) is now a {@code $export} parameter and arrives on
 * {@code BulkExportJobParameters}.
 *
 * <p>Read from system properties, then environment variables:
 * <pre>
 *   ECDS_OUTPUT_FORMAT   the _outputFormat value that selects this converter   (default text/csv)
 *   ECDS_FALLBACK_FEED   athena | konza, for records with no data-origin tag  (default athena)
 *   ECDS_CACHE_SIZE      entries per resolver cache                            (default 50000)
 *   ECDS_AUDIT_TYPE      the "type" the audit file is labelled with in the export manifest
 *                        (default EcdsAudit); empty disables the audit file
 *   ECDS_MAX_FILE_BYTES  start a new CSV file once one reaches this size (default 100 MB).
 *                        Horizon's limit is 5 GB per file; each file is also a Binary in the
 *                        database, so keep this far below that
 *   ECDS_PAGE_SIZE       resources per storage query when walking a patient's records (default 200)
 * </pre>
 *
 * @param outputFormat the media type a caller passes as {@code _outputFormat} to get ECDS;
 *                     compared case-insensitively, ignoring parameters such as charset
 * @param fallbackFeed used only for records that carry no data-origin tag
 * @param cacheSize    maximum entries in each resolver cache
 * @param auditType    manifest label for the audit file, or empty for no audit file
 * @param maxFileBytes size at which a CSV file is closed and a new one started
 * @param pageSize     resources per storage query when walking a patient's records
 */
public record EcdsConverterConfig(String outputFormat, String fallbackFeed, long cacheSize, String auditType,
                                  long maxFileBytes, int pageSize) {

    private static final Logger ourLog = LoggerFactory.getLogger(EcdsConverterConfig.class);

    public static final String DEFAULT_OUTPUT_FORMAT = "text/csv";
    public static final long DEFAULT_CACHE_SIZE = 50_000;
    public static final String DEFAULT_AUDIT_TYPE = "EcdsAudit";
    public static final long DEFAULT_MAX_FILE_BYTES = 100L * 1024 * 1024;
    public static final int DEFAULT_PAGE_SIZE = 200;

    /**
     * The ceiling {@code maxFileBytes} is clamped to.
     *
     * <p>A file is built in a {@code ByteArrayOutputStream} and handed over as one {@code byte[]},
     * so it can never exceed {@link Integer#MAX_VALUE}, and the stream doubles its buffer, so the
     * transient cost is twice the finished file — on several batch worker threads at once.
     * Horizon's own 5 GB per-file limit is not a sensible value to set here: it is a limit on
     * what they will accept, not a target, and setting it would run the node out of heap long
     * before a file got there. 512 MiB is already far larger than anything useful.
     */
    public static final long MAX_SAFE_FILE_BYTES = 512L * 1024 * 1024;

    /**
     * How long a resolved resource or document may be reused.
     *
     * <p>The converter — and so its caches — is created once and held for the life of the Smile
     * process, because sharing the caches across a job's chunks is the whole point of sharing
     * it. Without an expiry those entries also survive <em>between</em> jobs: tonight's export
     * would answer a Patient lookup from a copy read during last night's, and a reference that
     * resolved to nothing then would still resolve to nothing now even though the resource has
     * since been loaded. A HEDIS submission built from month-old demographics is the kind of
     * error nobody notices. Ten minutes is far longer than the gap between two chunks of one
     * job and far shorter than the gap between two nightly runs.
     */
    public static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(10);

    public EcdsConverterConfig {
        if (outputFormat == null || outputFormat.isBlank()) {
            throw new IllegalArgumentException("outputFormat is required");
        }
        // A wrong feed name would mislabel every row with another feed's constants, so this one
        // stays fatal: a module that refuses to start is better than a plausible-looking file.
        FeedConstants.forFeed(fallbackFeed);
        if (cacheSize <= 0) {
            throw new IllegalArgumentException("cacheSize must be positive");
        }
        auditType = auditType == null ? "" : auditType.trim();
        if (maxFileBytes <= 0) {
            throw new IllegalArgumentException("maxFileBytes must be positive");
        }
        if (maxFileBytes > MAX_SAFE_FILE_BYTES) {
            ourLog.warn("ECDS: maxFileBytes {} is above the {} byte ceiling a single in-memory"
                            + " file can safely reach; using the ceiling instead",
                    maxFileBytes, MAX_SAFE_FILE_BYTES);
            maxFileBytes = MAX_SAFE_FILE_BYTES;
        }
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
    }

    public static EcdsConverterConfig defaults() {
        return new EcdsConverterConfig(DEFAULT_OUTPUT_FORMAT, FeedConstants.DEFAULT_FEED,
                DEFAULT_CACHE_SIZE, DEFAULT_AUDIT_TYPE, DEFAULT_MAX_FILE_BYTES, DEFAULT_PAGE_SIZE);
    }

    public static EcdsConverterConfig fromEnvironment() {
        return new EcdsConverterConfig(
                nonBlank("ECDS_OUTPUT_FORMAT", DEFAULT_OUTPUT_FORMAT),
                nonBlank("ECDS_FALLBACK_FEED", FeedConstants.DEFAULT_FEED),
                number("ECDS_CACHE_SIZE", DEFAULT_CACHE_SIZE),
                // The one setting where empty is a real value: it switches the audit file off.
                setting("ECDS_AUDIT_TYPE", DEFAULT_AUDIT_TYPE),
                number("ECDS_MAX_FILE_BYTES", DEFAULT_MAX_FILE_BYTES),
                (int) number("ECDS_PAGE_SIZE", DEFAULT_PAGE_SIZE));
    }

    /** A setting that has no meaningful empty value, so blank falls back to the default. */
    private static String nonBlank(String theName, String theDefault) {
        String value = setting(theName, theDefault);
        return value.isBlank() ? theDefault : value;
    }

    /**
     * A numeric setting, or the default when it is missing or unreadable.
     *
     * <p>This runs inside the no-argument constructor Smile calls to instantiate the interceptor.
     * A typo in one tuning number must not stop the bean from being created — the symptom would
     * be {@code $export?_outputFormat=text/csv} coming back "Unsupported output format", which
     * says nothing about the real cause.
     */
    private static long number(String theName, long theDefault) {
        String value = nonBlank(theName, Long.toString(theDefault));
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            ourLog.warn("ECDS: {}={} is not a number; using {}", theName, value, theDefault);
            return theDefault;
        }
    }

    public EcdsConverterConfig withMaxFileBytes(long theBytes) {
        return new EcdsConverterConfig(outputFormat, fallbackFeed, cacheSize, auditType, theBytes, pageSize);
    }

    public EcdsConverterConfig withAuditType(String theAuditType) {
        return new EcdsConverterConfig(outputFormat, fallbackFeed, cacheSize, theAuditType, maxFileBytes, pageSize);
    }

    public boolean writesAudit() {
        return !auditType.isEmpty();
    }

    /** True when {@code theRequested} names this converter's format, parameters aside. */
    public boolean matches(String theRequested) {
        if (theRequested == null) {
            return false;
        }
        return mediaType(theRequested).equalsIgnoreCase(mediaType(outputFormat));
    }

    private static String mediaType(String theValue) {
        int semicolon = theValue.indexOf(';');
        return (semicolon < 0 ? theValue : theValue.substring(0, semicolon)).trim();
    }

    /**
     * A setting, or the default when it is not set at all.
     *
     * <p>"Set to the empty string" is distinct from "not set": {@code ECDS_AUDIT_TYPE=} is the
     * documented way to turn the audit file off, and treating blank as absent silently put
     * {@code EcdsAudit} back and left the audit on.
     */
    private static String setting(String theName, String theDefault) {
        String value = System.getProperty(theName);
        if (value == null) {
            value = System.getenv(theName);
        }
        return value == null ? theDefault : value.trim();
    }
}
