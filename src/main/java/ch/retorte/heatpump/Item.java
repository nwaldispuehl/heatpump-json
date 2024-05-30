package ch.retorte.heatpump;

import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.json.bind.annotation.JsonbTransient;

import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

public class Item {

    // ---- Fields

    private final ResourceBundle bundle;
    private final String name;
    private final String nodeId;
    private final HeatpumpDataConverter.UnitInfo unitInfo;
    private String raw = null;
    private String textual;
    private Number numeric;

    private Item parent;
    List<Item> children = new ArrayList<>();


    // ---- Constructor

    public Item(ResourceBundle bundle, String name, String nodeId, HeatpumpDataConverter.UnitInfo unitInfo) {
        this.bundle = bundle;
        this.name = name;
        this.nodeId = nodeId;
        this.unitInfo = unitInfo;
    }


    // ---- Heritage methods

    public void addChildren(List<Item> children) {
        this.children.addAll(children);
        this.children.forEach(c -> c.setParent(this));
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    public void setParent(Item parent) {
        this.parent = parent;
    }

    @JsonbTransient
    public Item getParent() {
        return parent;
    }

    public boolean hasParent() {
        return parent != null;
    }

    @JsonbTransient
    public List<Item> getChildren() {
        return children;
    }

    private List<Item> toRoot() {
        List<Item> parents = new ArrayList<>();
        Item p = this;
        while (p.hasParent()) {
            p = p.getParent();
            parents.add(p);
        }
        return parents;
    }


    // ---- Property methods

    @JsonbTransient
    public String getNodeId() {
        return nodeId;
    }

    public void setRawValue(String rawValue) {
        if (unitInfo == null) {
            throw new IllegalStateException("Unit info not set.");
        }

        this.raw = rawValue;

        unitInfo.unit().convertWith(bundle, rawValue,
            textual -> this.textual = textual,
            numeric -> this.numeric = numeric
        );
    }

    @JsonbProperty
    public String getId() {
        return unitInfo.identifier();
    }

    @JsonbProperty
    public String getCategory() {
        return toRoot().reversed().stream().map(Item::getId).collect(Collectors.joining("."));
    }

    @JsonbProperty
    public String getName() {
        return name;
    }

    @JsonbProperty
    public String getUnit() {
        return unitInfo.unit().marker();
    }

    @JsonbProperty
    public String getTextual() {
        return textual;
    }

    @JsonbProperty
    public Number getNumeric() {
        return numeric;
    }

    @JsonbTransient
    public boolean isLeaf() {
        return raw != null;
    }

}
