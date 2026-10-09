package org.horizon.ecds;

import java.util.EnumSet;

/**
 * Audits a built row for blank mandatory columns before it is written.
 *
 * <p>{@link RequiredColumns} lists what Horizon requires on every row, but the POC only consulted
 * it from tests: a row shipped with a blank {@code MemberID} or {@code MemberDOB} and nothing in
 * the audit file said so. This closes that gap without changing any column derivation. The row
 * still ships — the mapping's rule is "emit incomplete and flag", never "invent a value".
 *
 * <p>{@code HCFAPOS_00} is skipped here because {@link ProviderColumns} already audits it with a
 * more specific reason. A row whose Patient could not be resolved gets {@code MEMBER_KEY_MISSING}
 * from the builder and, from here, one entry per blank member column — the first says why, the
 * rest say what is missing.
 */
public final class RequiredColumnsAudit {

    private static final EnumSet<EcdsColumn> CHECKED;

    static {
        CHECKED = EnumSet.copyOf(RequiredColumns.mandatory());
        CHECKED.remove(EcdsColumn.HCFAPOS_00);
    }

    private RequiredColumnsAudit() {
    }

    /** One {@code REQUIRED_FIELD_MISSING} warning per blank mandatory column, in column order. */
    public static void check(EcdsRow theRow, AuditLog theAudit) {
        String resource = theRow.get(EcdsColumn.Ecdsaltid_00);
        for (EcdsColumn column : CHECKED) {
            if (theRow.get(column).isEmpty()) {
                theAudit.warn(AuditCode.REQUIRED_FIELD_MISSING, resource, column,
                        "required column is blank");
            }
        }
    }
}
