package ru.minecomplex.mac.entity.attribute;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of {@code AttributeInstance}: base value plus modifiers, evaluated in vanilla's order ({@code ADD_VALUE} into
 * the base, {@code ADD_MULTIPLIED_BASE} on that base, {@code ADD_MULTIPLIED_TOTAL} multiplied one after another, then
 * sanitized). The per-operation tables keep fastutil's order ({@link ModifierTable}); {@code modifierById} is an
 * {@code Object2ObjectArrayMap}, i.e. insertion order, which {@link #removeModifiers()} iterates. The modifier maps are
 * created with the first modifier: most instances never get one.
 */
public final class AttributeInstance {
    private final Attribute attribute;
    /** {@code null} until a modifier was added. */
    private Map<AttributeModifier.Operation, ModifierTable> byOperation;
    /** {@code null} until a modifier was added. */
    private Map<Identifier, AttributeModifier> byId;
    private double baseValue;
    private boolean dirty = true;
    private double cachedValue;

    public AttributeInstance(Attribute attribute, double baseValue) {
        this.attribute = attribute;
        this.baseValue = baseValue;
    }

    public Attribute attribute() {
        return attribute;
    }

    public double baseValue() {
        return baseValue;
    }

    public void setBaseValue(double baseValue) {
        if (baseValue != this.baseValue) {
            this.baseValue = baseValue;
            dirty = true;
        }
    }

    public boolean hasModifier(Identifier id) {
        return byId != null && byId.get(id) != null;
    }

    public List<AttributeModifier> modifiers() {
        return byId == null ? List.of() : List.copyOf(byId.values());
    }

    private Map<Identifier, AttributeModifier> byId() {
        if (byId == null) byId = new LinkedHashMap<>();
        return byId;
    }

    private ModifierTable table(AttributeModifier.Operation operation) {
        if (byOperation == null) byOperation = new EnumMap<>(AttributeModifier.Operation.class);
        return byOperation.computeIfAbsent(operation, o -> new ModifierTable());
    }

    /** {@code addTransientModifier}: throws if a modifier with the id is present, like vanilla. */
    public void addModifier(AttributeModifier modifier) {
        if (byId().putIfAbsent(modifier.id(), modifier) != null) {
            throw new IllegalArgumentException("Modifier is already applied on this attribute!");
        }
        table(modifier.operation()).put(modifier.id(), modifier);
        dirty = true;
    }

    /** {@code addOrUpdateTransientModifier}. */
    public void addOrUpdateModifier(AttributeModifier modifier) {
        AttributeModifier old = byId().put(modifier.id(), modifier);
        if (!modifier.equals(old)) {
            table(modifier.operation()).put(modifier.id(), modifier);
            dirty = true;
        }
    }

    public boolean removeModifier(Identifier id) {
        AttributeModifier modifier = byId == null ? null : byId.remove(id);
        if (modifier == null) {
            return false;
        }
        table(modifier.operation()).remove(id);
        dirty = true;
        return true;
    }

    /** {@code removeModifiers()}: removes one by one in {@code modifierById} order. */
    public void removeModifiers() {
        if (byId == null || byId.isEmpty()) return;
        for (AttributeModifier modifier : new ArrayList<>(byId.values())) {
            removeModifier(modifier.id());
        }
    }

    public double value() {
        if (dirty) {
            cachedValue = calculate();
            dirty = false;
        }
        return cachedValue;
    }

    private double calculate() {
        double base = baseValue;
        for (AttributeModifier modifier : modifiers(AttributeModifier.Operation.ADD_VALUE)) {
            base += modifier.amount();
        }
        double result = base;
        for (AttributeModifier modifier : modifiers(AttributeModifier.Operation.ADD_MULTIPLIED_BASE)) {
            result += base * modifier.amount();
        }
        for (AttributeModifier modifier : modifiers(AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)) {
            result *= 1.0 + modifier.amount();
        }
        return attribute.sanitize(result);
    }

    private List<AttributeModifier> modifiers(AttributeModifier.Operation operation) {
        ModifierTable table = byOperation == null ? null : byOperation.get(operation);
        return table == null ? List.of() : table.values();
    }

    /**
     * {@code AttributeInstance.replaceFrom}: the base value is assigned directly (no equality check), the modifiers by
     * id in {@code other}'s order, and each operation's table is a fresh map filled by {@code putAll}.
     */
    public void replaceFrom(AttributeInstance other) {
        baseValue = other.baseValue;
        byId = other.byId == null ? null : new LinkedHashMap<>(other.byId);
        if (other.byOperation == null) {
            byOperation = null;
        } else {
            byOperation = new EnumMap<>(AttributeModifier.Operation.class);
            other.byOperation.forEach((operation, table) -> byOperation.put(operation, ModifierTable.putAllOf(table)));
        }
        dirty = true;
    }

    public AttributeInstance copy() {
        AttributeInstance copy = new AttributeInstance(attribute, baseValue);
        if (byId != null) copy.byId = new LinkedHashMap<>(byId);
        if (byOperation != null) {
            copy.byOperation = new EnumMap<>(AttributeModifier.Operation.class);
            byOperation.forEach((operation, table) -> copy.byOperation.put(operation, table.copy()));
        }
        copy.dirty = dirty;
        copy.cachedValue = cachedValue;
        return copy;
    }
}
