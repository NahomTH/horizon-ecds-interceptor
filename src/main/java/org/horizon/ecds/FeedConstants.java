package org.horizon.ecds;

import java.util.Locale;

/**
 * The column values that are fixed for a whole submission file rather than derived per record.
 *
 * <p>{@code ClientSystemOfRecordID} and {@code Datasourcename} identify the source feed and are
 * intentionally identical on every row (confirmed with Horizon 2026-09-16).
 *
 * <p>{@code SuppSource} is pinned to {@code H} for every feed, as the mapping doc directs. The doc
 * also flags that a blanket {@code H} (Registry/HIE Non-Standard) sits oddly with an
 * {@code EHR} system-of-record, and that the question is still open with Horizon — so this is kept
 * here beside the feed values, ready to become feed-derived in one line if the answer changes.
 */
public record FeedConstants(String referenceId, String clientSystemOfRecordId, String suppSource) {

    /** Fixed for the MY26 file; the MY27 file uses the equivalent MY27 literal. */
    private static final String REFERENCE_ID = "SMILEMY26";

    private static final String SUPP_SOURCE = "H";

    public static final String DEFAULT_FEED = "athena";

    /**
     * @param theFeed {@code athena} or {@code konza}, case-insensitive
     * @throws IllegalArgumentException for any other feed
     */
    public static FeedConstants forFeed(String theFeed) {
        String feed = theFeed == null ? "" : theFeed.trim().toLowerCase(Locale.ROOT);
        String systemOfRecord = switch (feed) {
            case "athena" -> "EHR";
            case "konza" -> "HIE";
            default -> throw new IllegalArgumentException(
                    "Unknown feed '" + theFeed + "'. Expected one of: athena, konza");
        };
        return new FeedConstants(REFERENCE_ID, systemOfRecord, SUPP_SOURCE);
    }

    public static FeedConstants defaultFeed() {
        return forFeed(DEFAULT_FEED);
    }

    /** The same value as {@link #clientSystemOfRecordId()}, by Horizon's design. */
    public String datasourcename() {
        return clientSystemOfRecordId();
    }

    /** Stamps the four constant columns onto a row. */
    public void applyTo(EcdsRow theRow) {
        theRow.set(EcdsColumn.ReferenceID, referenceId());
        theRow.set(EcdsColumn.ClientSystemOfRecordID, clientSystemOfRecordId());
        theRow.set(EcdsColumn.Datasourcename, datasourcename());
        theRow.set(EcdsColumn.SuppSource, suppSource());
    }
}
