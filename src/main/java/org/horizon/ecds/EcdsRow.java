package org.horizon.ecds;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One row of the ECDS output.
 *
 * <p>Unset columns render as empty strings, which is what the 11 always-blank columns and any
 * unresolvable value need. {@link #copy()} is what makes row explosion cheap: an extractor
 * builds the columns shared by every row once, then copies it per CPT code or per blood-pressure
 * component and overwrites only what differs.
 */
public class EcdsRow {

    private final Map<EcdsColumn, String> myValues;

    public EcdsRow() {
        myValues = new EnumMap<>(EcdsColumn.class);
    }

    private EcdsRow(Map<EcdsColumn, String> theValues) {
        myValues = new EnumMap<>(theValues);
    }

    /** Sets a column; null and blank are stored as empty. */
    public EcdsRow set(EcdsColumn theColumn, String theValue) {
        myValues.put(theColumn, theValue == null || theValue.isBlank() ? "" : theValue);
        return this;
    }

    public String get(EcdsColumn theColumn) {
        return myValues.getOrDefault(theColumn, "");
    }

    /** An independent copy, for emitting several rows from one source record. */
    public EcdsRow copy() {
        return new EcdsRow(myValues);
    }

    /** The 58 values in column order, ready for the CSV writer. */
    public List<String> toValues() {
        List<String> values = new ArrayList<>(EcdsColumn.values().length);
        for (EcdsColumn column : EcdsColumn.values()) {
            values.add(get(column));
        }
        return values;
    }
}
