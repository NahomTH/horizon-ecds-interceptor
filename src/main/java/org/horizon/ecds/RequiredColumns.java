package org.horizon.ecds;

import java.util.Set;

/**
 * The columns Horizon requires on every row.
 *
 * <p>Taken from the {@code Required} column of {@code notes/ECDS mapping.csv}, which marks each
 * column Yes, Conditional, No or Blank. Only an unqualified <em>Yes</em> is mandatory:
 *
 * <ul>
 *   <li><em>Conditional</em> depends on what the source record happens to carry — a CVX code
 *       belongs on an immunisation row and nowhere else.</li>
 *   <li><em>Blank</em> means Horizon fills it downstream, so an empty value is correct.</li>
 *   <li>{@code ProviderKey} is marked "Yes - see transform", and that transform explicitly
 *       permits a blank plus an audit entry when no NPI or TIN can be reached.</li>
 * </ul>
 *
 * <p>Listed here rather than read at runtime because {@code notes/} is not on the packaged
 * classpath. {@code RequiredColumnsTest} reconciles the two and fails the build if they drift.
 */
public final class RequiredColumns {

    private static final Set<EcdsColumn> MANDATORY = Set.of(
            EcdsColumn.MemberKey,
            EcdsColumn.ReferenceID,
            EcdsColumn.DOS,
            EcdsColumn.DOSThru,
            EcdsColumn.RxProviderFlag,
            EcdsColumn.SuppSource,
            EcdsColumn.Ecdsaltid_00,
            EcdsColumn.Ecdsaltid_01,
            EcdsColumn.Sexparameterforclinicaluse,
            EcdsColumn.ClientSystemOfRecordID,
            EcdsColumn.HCFAPOS_00,
            EcdsColumn.Datasourcename,
            EcdsColumn.MemberID,
            EcdsColumn.MemberLastName,
            EcdsColumn.MemberFirstName,
            EcdsColumn.MemberDOB,
            EcdsColumn.MemberGender);

    private RequiredColumns() {
    }

    /** The columns that must carry a value on every row. */
    public static Set<EcdsColumn> mandatory() {
        return MANDATORY;
    }

    public static boolean isMandatory(EcdsColumn theColumn) {
        return MANDATORY.contains(theColumn);
    }
}
