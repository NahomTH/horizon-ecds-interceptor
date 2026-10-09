package org.horizon.ecds;

/** FHIR code system URLs used by the ECDS mapping, and the code formatting Horizon expects. */
public final class CodeSystems {

    public static final String LOINC = "http://loinc.org";
    public static final String SNOMED = "http://snomed.info/sct";
    public static final String ICD_10_CM = "http://hl7.org/fhir/sid/icd-10-cm";
    public static final String ICD_10_PCS = "http://www.cms.gov/Medicare/Coding/ICD10";
    public static final String ICD_9_CM = "urn:oid:2.16.840.1.113883.6.103";
    public static final String CPT = "http://www.ama-assn.org/go/cpt";
    public static final String HCPCS = "urn:oid:2.16.840.1.113883.6.285";
    public static final String HCPCS_BLUEBUTTON = "https://bluebutton.cms.gov/resources/codesystem/hcpcs";
    public static final String RXNORM = "http://www.nlm.nih.gov/research/umls/rxnorm";
    public static final String CVX = "http://hl7.org/fhir/sid/cvx";
    public static final String RADLEX = "http://radlex.org";
    public static final String NULL_FLAVOR = "http://terminology.hl7.org/CodeSystem/v3-NullFlavor";

    private CodeSystems() {
    }

    /**
     * Removes the decimal point from an ICD code, per the Horizon layout: {@code V39.00XS}
     * becomes {@code V3900XS}.
     *
     * <p>Only ever applied to ICD columns. LOINC must keep its hyphen and is never passed here
     * for alteration — a hyphen is not a decimal point, so it survives regardless.
     */
    public static String stripDecimal(String theCode) {
        return theCode == null ? "" : theCode.replace(".", "");
    }
}
