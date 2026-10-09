package org.horizon.ecds;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EcdsRowTest {

    @Test
    void unsetColumnsRenderAsEmptyStrings() {
        EcdsRow row = new EcdsRow();

        assertThat(row.get(EcdsColumn.MemberKey)).isEmpty();
        assertThat(row.toValues()).hasSize(58).allMatch(String::isEmpty);
    }

    @Test
    void valuesAreReturnedInColumnOrder() {
        EcdsRow row = new EcdsRow()
                .set(EcdsColumn.MemberKey, "11111")
                .set(EcdsColumn.MemberGender, "F");

        assertThat(row.toValues().get(0)).isEqualTo("11111");
        assertThat(row.toValues().get(57)).isEqualTo("F");
    }

    @Test
    void nullAndBlankAreStoredAsEmpty() {
        EcdsRow row = new EcdsRow()
                .set(EcdsColumn.MemberKey, null)
                .set(EcdsColumn.MemberID, "   ");

        assertThat(row.get(EcdsColumn.MemberKey)).isEmpty();
        assertThat(row.get(EcdsColumn.MemberID)).isEmpty();
    }

    @Test
    void copyCarriesTheSharedColumnsButIsIndependent() {
        // The row-explosion pattern: shared columns built once, then varied per code.
        EcdsRow base = new EcdsRow().set(EcdsColumn.MemberKey, "11111");

        EcdsRow first = base.copy().set(EcdsColumn.CPTPx, "95251");
        EcdsRow second = base.copy().set(EcdsColumn.CPTPx, "95249");

        assertThat(first.get(EcdsColumn.MemberKey)).isEqualTo("11111");
        assertThat(second.get(EcdsColumn.MemberKey)).isEqualTo("11111");
        assertThat(first.get(EcdsColumn.CPTPx)).isEqualTo("95251");
        assertThat(second.get(EcdsColumn.CPTPx)).isEqualTo("95249");
        assertThat(base.get(EcdsColumn.CPTPx)).isEmpty();
    }
}
