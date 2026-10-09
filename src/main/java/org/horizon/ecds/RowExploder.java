package org.horizon.ecds;

import com.fasterxml.jackson.databind.JsonNode;
import org.horizon.fhir.FhirPaths;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns one source record into zero or more ECDS rows.
 *
 * <p>Three rules multiply, and each belongs to one resource type: a Procedure with several CPT
 * codes, a blood-pressure Observation with components, and a panel Observation with
 * {@code hasMember} children. No record can satisfy two, so they are kept separate rather than
 * composed into a general product — that would be machinery for a case that cannot arise.
 *
 * <p>The base row, holding everything the variants share, is built by the caller and copied per
 * variant, so only the differing columns are overwritten.
 */
public final class RowExploder {

    private RowExploder() {
    }

    /**
     * @param theBaseRow the columns already derived for the record, shared by every row it yields
     * @return zero rows for a suppressed record, otherwise one or more
     */
    public static List<EcdsRow> explode(JsonNode theRecord, EcdsRow theBaseRow) {
        return switch (theRecord.path("resourceType").asText()) {
            case "Procedure" -> byCptCode(theRecord, theBaseRow);
            case "Observation" -> byObservationPart(theRecord, theBaseRow);
            case "Immunization" -> completedOnly(theRecord, theBaseRow);
            default -> List.of(theBaseRow);
        };
    }

    /** Horizon takes one CPT per row, so a Procedure carrying several becomes several rows. */
    private static List<EcdsRow> byCptCode(JsonNode theRecord, EcdsRow theBaseRow) {
        List<String> cptCodes = FhirPaths.codes(theRecord.get("code"), CodeSystems.CPT);
        if (cptCodes.size() <= 1) {
            return List.of(theBaseRow);
        }
        List<EcdsRow> rows = new ArrayList<>(cptCodes.size());
        for (String code : cptCodes) {
            rows.add(theBaseRow.copy().set(EcdsColumn.CPTPx, code));
        }
        return rows;
    }

    private static List<EcdsRow> byObservationPart(JsonNode theRecord, EcdsRow theBaseRow) {
        List<JsonNode> components = FhirPaths.array(theRecord, "component").toList();
        if (!components.isEmpty()) {
            return byComponent(components, theBaseRow);
        }
        List<JsonNode> children = panelChildren(theRecord);
        if (!children.isEmpty()) {
            return byPanelChild(children, theBaseRow);
        }
        return List.of(theBaseRow);
    }

    /**
     * A blood-pressure Observation carries systolic and diastolic as components. Each becomes a
     * row bearing that component's own LOINC, not the panel code.
     */
    private static List<EcdsRow> byComponent(List<JsonNode> theComponents, EcdsRow theBaseRow) {
        List<EcdsRow> rows = new ArrayList<>(theComponents.size());
        for (JsonNode component : theComponents) {
            EcdsRow row = theBaseRow.copy();
            FhirPaths.firstCode(component.get("code"), CodeSystems.LOINC)
                    .ifPresent(code -> row.set(EcdsColumn.LOINC, code));
            applyValue(component, row);
            rows.add(row);
        }
        return rows;
    }

    /** One row per question in a multi-question instrument. */
    private static List<EcdsRow> byPanelChild(List<JsonNode> theChildren, EcdsRow theBaseRow) {
        List<EcdsRow> rows = new ArrayList<>(theChildren.size());
        for (JsonNode child : theChildren) {
            EcdsRow row = theBaseRow.copy();
            FhirPaths.firstCode(child.get("code"), CodeSystems.LOINC)
                    .ifPresent(code -> row.set(EcdsColumn.LOINC, code));
            applyValue(child, row);
            rows.add(row);
        }
        return rows;
    }

    /** Children are contained in the parent; a reference that does not resolve is skipped. */
    private static List<JsonNode> panelChildren(JsonNode theRecord) {
        List<JsonNode> children = new ArrayList<>();
        FhirPaths.array(theRecord, "hasMember").forEach(member ->
                FhirPaths.reference(member)
                        .filter(reference -> reference.startsWith("#"))
                        .flatMap(reference -> containedById(theRecord, reference.substring(1)))
                        .ifPresent(children::add));
        return children;
    }

    /**
     * A numeric answer goes to Result, a coded one to LOINCAnswer. The panel children in the
     * export carry valueInteger, so the score lands in Result rather than being dropped.
     */
    private static void applyValue(JsonNode theNode, EcdsRow theRow) {
        numericValue(theNode).ifPresent(value -> {
            theRow.set(EcdsColumn.Result, value);
            theRow.set(EcdsColumn.LOINCAnswer, "");
        });
        JsonNode coded = theNode.get("valueCodeableConcept");
        FhirPaths.firstCode(coded, CodeSystems.SNOMED)
                .or(() -> FhirPaths.firstCode(coded, CodeSystems.LOINC))
                .ifPresent(code -> {
                    theRow.set(EcdsColumn.LOINCAnswer, code);
                    theRow.set(EcdsColumn.Result, "");
                });
    }

    private static Optional<String> numericValue(JsonNode theNode) {
        JsonNode quantity = theNode.get("valueQuantity");
        JsonNode value = quantity != null ? quantity.get("value") : theNode.get("valueInteger");
        if (value == null || !value.isNumber()) {
            return Optional.empty();
        }
        return Optional.of(new BigDecimal(value.asText()).stripTrailingZeros().toPlainString());
    }

    /** Refusals and errors are suppressed entirely, not emitted with a reason code. */
    private static List<EcdsRow> completedOnly(JsonNode theRecord, EcdsRow theBaseRow) {
        String status = FhirPaths.primitive(theRecord, "status").orElse("");
        return "completed".equals(status) ? List.of(theBaseRow) : List.of();
    }

    private static Optional<JsonNode> containedById(JsonNode theParent, String theId) {
        return FhirPaths.array(theParent, "contained")
                .filter(contained -> FhirPaths.primitive(contained, "id").filter(theId::equals).isPresent())
                .findFirst();
    }
}
