package org.horizon.ecds;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedConstantsTest {

    @Test
    void athenaFeedEmitsEhr() {
        FeedConstants constants = FeedConstants.forFeed("athena");

        assertThat(constants.clientSystemOfRecordId()).isEqualTo("EHR");
        assertThat(constants.datasourcename()).isEqualTo("EHR");
    }

    @Test
    void konzaFeedEmitsHie() {
        FeedConstants constants = FeedConstants.forFeed("konza");

        assertThat(constants.clientSystemOfRecordId()).isEqualTo("HIE");
        assertThat(constants.datasourcename()).isEqualTo("HIE");
    }

    @Test
    void theTwoFeedColumnsAlwaysCarryTheSameValue() {
        // Horizon confirmed 2026-09-16 that these are intentionally identical on every row.
        for (String feed : new String[] {"athena", "konza"}) {
            FeedConstants constants = FeedConstants.forFeed(feed);
            assertThat(constants.datasourcename()).isEqualTo(constants.clientSystemOfRecordId());
        }
    }

    @Test
    void feedNameIsCaseInsensitive() {
        assertThat(FeedConstants.forFeed("ATHENA").clientSystemOfRecordId()).isEqualTo("EHR");
        assertThat(FeedConstants.forFeed("Konza").clientSystemOfRecordId()).isEqualTo("HIE");
    }

    @Test
    void anUnknownFeedIsRejected() {
        assertThatThrownBy(() -> FeedConstants.forFeed("acme"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acme");
    }

    @Test
    void referenceIdIsTheFixedMeasurementYearConstant() {
        // Fixed on every row; per-record traceability lives in Ecdsaltid_00.
        assertThat(FeedConstants.forFeed("athena").referenceId()).isEqualTo("SMILEMY26");
    }

    @Test
    void suppSourceIsHRegardlessOfFeed() {
        // Horizon directs a blanket 'H'. The mapping doc flags this as inconsistent with
        // ClientSystemOfRecordID=EHR and still pending, so it is deliberately not feed-derived.
        assertThat(FeedConstants.forFeed("athena").suppSource()).isEqualTo("H");
        assertThat(FeedConstants.forFeed("konza").suppSource()).isEqualTo("H");
    }

    @Test
    void theDefaultFeedIsAthena() {
        assertThat(FeedConstants.defaultFeed().clientSystemOfRecordId()).isEqualTo("EHR");
    }

    @Test
    void constantsAreAppliedToARow() {
        EcdsRow row = new EcdsRow();

        FeedConstants.forFeed("konza").applyTo(row);

        assertThat(row.get(EcdsColumn.ReferenceID)).isEqualTo("SMILEMY26");
        assertThat(row.get(EcdsColumn.ClientSystemOfRecordID)).isEqualTo("HIE");
        assertThat(row.get(EcdsColumn.Datasourcename)).isEqualTo("HIE");
        assertThat(row.get(EcdsColumn.SuppSource)).isEqualTo("H");
    }
}
