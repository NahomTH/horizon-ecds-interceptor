package org.horizon.ecds;

/**
 * Why a row was flagged. Stable names, so the audit file can be grepped and counted.
 */
public enum AuditCode {

    /** No NPI on the performer and no Organization TIN to fall back to. */
    PROVIDER_KEY_MISSING,

    /** No date element the mapping recognises for this resource type. */
    DOS_MISSING,

    /**
     * The record stated no date of its own, so the date of the encounter it belongs to was
     * used. An inference, not a statement from the source.
     */
    DOS_FROM_ENCOUNTER,

    /** birthDate is present but not a full YYYY-MM-DD, so it cannot satisfy varchar(10). */
    MEMBER_DOB_PARTIAL,

    /** A reference pointed at a resource that is not in the export. */
    UNRESOLVED_REFERENCE,

    /** No Patient could be resolved, so the member columns are blank. */
    MEMBER_KEY_MISSING,

    /** A NUCC taxonomy code the crosswalk does not cover, so no ProviderType could be set. */
    PROVIDER_TYPE_UNMAPPED,

    /** More distinct provider types than the six ProviderType columns can hold. */
    PROVIDER_TYPE_OVERFLOW,

    /**
     * A column the spec marks Required=Yes could not be populated from the source. The row
     * ships incomplete rather than carrying an invented value.
     */
    REQUIRED_FIELD_MISSING,

    /** Observation carried both components and hasMember; components won. */
    OBSERVATION_COMPONENT_AND_HASMEMBER
}
