package org.horizon.ecds;

import java.util.Arrays;
import java.util.List;

/**
 * The 58 ECDS output columns, in submission order.
 *
 * <p>Declaration order <em>is</em> the column order of the generated CSV. The constant names
 * mirror {@code notes/ECDS mapping.csv} exactly, including its inconsistent casing
 * ({@code Sexparameterforclinicaluse}, {@code HOMEGROWN_MOD_00}), so
 * {@code EcdsColumnTest.columnEnumMatchesTheMappingSpecInOrder} can compare them as plain
 * strings. That test fails the build if the spec and this enum ever diverge.
 *
 * <p>Columns fall into three groups, which live in code rather than as enum state:
 * 11 are blank on every row, 4 are constants, and 43 are derived from FHIR.
 */
public enum EcdsColumn {

    MemberKey,
    ProviderKey,
    ReferenceID,
    DOS,
    DOSThru,
    ProviderType_00,
    ProviderType_01,
    ProviderType_02,
    ProviderType_03,
    ProviderType_04,
    ProviderType_05,
    ProviderTaxonomy,
    CPTPx,
    HCPCSPx,
    LOINC,
    SNOMED,
    ICDDX,
    RxNorm,
    CVX,
    Modifier,
    RxProviderFlag,
    PCPFlag,
    QuantityDispensed,
    ICDPx,
    ICDPx10,
    SuppSource,
    Result,
    CPTmod,
    Ecdsaltid_00,
    Ecdsaltid_01,
    Ecdsaltid_02,
    HCPCSmod,
    LOINCAnswer,
    RadLex,
    Sexparameterforclinicaluse,
    ClientSystemOfRecordID,
    HCFAPOS_00,
    ICDDx10_00,
    ICDDx10_01,
    ICDDx10_02,
    ICDDx10_03,
    ICDDx10_04,
    ICDDx10_05,
    ICDDx10_06,
    ICDDx10_07,
    ICDDx10_08,
    ICDDx10_09,
    Datasourcename,
    HomeGrownPX_00,
    HOMEGROWN_MOD_00,
    Measure,
    Measurename,
    Submeasure,
    MemberID,
    MemberLastName,
    MemberFirstName,
    MemberDOB,
    MemberGender;

    /** The CSV header, in output order. */
    public static List<String> header() {
        return Arrays.stream(values()).map(EcdsColumn::columnName).toList();
    }

    /** The name as it appears in the spec and in the CSV header. */
    public String columnName() {
        return name();
    }
}
