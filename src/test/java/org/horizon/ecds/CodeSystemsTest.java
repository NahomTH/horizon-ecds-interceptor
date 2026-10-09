package org.horizon.ecds;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodeSystemsTest {

    @Test
    void stripsTheDecimalFromAnIcdCode() {
        // Horizon's layout: V39.00XS -> V3900XS
        assertThat(CodeSystems.stripDecimal("V39.00XS")).isEqualTo("V3900XS");
        assertThat(CodeSystems.stripDecimal("Z00.00")).isEqualTo("Z0000");
        assertThat(CodeSystems.stripDecimal("E11.22")).isEqualTo("E1122");
    }

    @Test
    void leavesACodeWithNoDecimalUnchanged() {
        assertThat(CodeSystems.stripDecimal("F32A")).isEqualTo("F32A");
    }

    @Test
    void stripDecimalHandlesBlankInput() {
        assertThat(CodeSystems.stripDecimal("")).isEmpty();
        assertThat(CodeSystems.stripDecimal(null)).isEmpty();
    }

    @Test
    void keepsTheHyphenInALoincCode() {
        // The spec is explicit: LOINC must retain the dash before the final digit.
        assertThat(CodeSystems.stripDecimal("4548-4")).isEqualTo("4548-4");
    }
}
