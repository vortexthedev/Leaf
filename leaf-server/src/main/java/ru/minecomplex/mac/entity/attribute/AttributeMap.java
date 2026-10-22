package ru.minecomplex.mac.entity.attribute;

import java.util.EnumMap;
import java.util.Map;

/**
 * The attribute instances of an entity that movement reads: a player's created with {@link Attribute#playerBase()},
 * another living entity's with its type's {@code DefaultAttributes} supplier ({@link #forEntity}).
 */
public final class AttributeMap {
    private final Map<Attribute, AttributeInstance> instances = new EnumMap<>(Attribute.class);

    /**
     * A living entity's map from its type's supplier base values (attribute key → base). An attribute the supplier lacks
     * has no instance in vanilla; it gets the registry default here, which only matters if something reads it.
     */
    public static AttributeMap forEntity(Map<String, Double> supplier) {
        AttributeMap map = new AttributeMap();
        for (Attribute attribute : Attribute.ALL) {
            map.instances.put(attribute, new AttributeInstance(attribute, supplierBase(attribute, supplier)));
        }
        return map;
    }

    /**
     * The value of one attribute of a living entity, as {@link #forEntity} followed by {@link #apply} for each snapshot
     * would give it, without building the other instances.
     */
    public static double value(Attribute attribute, Map<String, Double> supplier, Iterable<AttributeSnapshot> snapshots) {
        AttributeInstance instance = null;
        for (var snapshot : snapshots) {
            if (byKey(snapshot.attribute()) != attribute) continue;
            if (instance == null) instance = new AttributeInstance(attribute, supplierBase(attribute, supplier));
            apply(instance, snapshot);
        }
        return instance == null ? attribute.sanitize(supplierBase(attribute, supplier)) : instance.value();
    }

    private static double supplierBase(Attribute attribute, Map<String, Double> supplier) {
        Double base = supplier.get(attribute.key());
        return base == null ? attribute.defaultValue() : base;
    }

    /**
     * {@code handleUpdateAttributes} for one snapshot: {@code setBaseValue}, {@code removeModifiers}, then
     * {@code addTransientModifier} for each modifier in wire order. Attributes movement does not read are skipped.
     */
    public void apply(AttributeSnapshot snapshot) {
        Attribute attribute = byKey(snapshot.attribute());
        if (attribute == null) return;
        apply(instances.get(attribute), snapshot);
    }

    private static void apply(AttributeInstance instance, AttributeSnapshot snapshot) {
        instance.setBaseValue(snapshot.base());
        instance.removeModifiers();
        for (var modifier : snapshot.modifiers()) {
            instance.addModifier(new AttributeModifier(Identifier.parse(modifier.id()), modifier.amount(),
                    switch (modifier.operation()) {
                        case 0 -> AttributeModifier.Operation.ADD_VALUE;
                        case 1 -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
                        case 2 -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
                        default -> throw new IllegalArgumentException("Unknown attribute operation " + modifier.operation());
                    }));
        }
    }

    /** The modelled attribute registered under {@code key}, {@code null} for any other. */
    public static Attribute byKey(String key) {
        for (Attribute attribute : Attribute.ALL) {
            if (attribute.key().equals(key)) return attribute;
        }
        var id = Identifier.parse(key);
        for (Attribute attribute : Attribute.ALL) {
            if (attribute.id().equals(id)) return attribute;
        }
        return null;
    }

    public static AttributeMap forPlayer() {
        AttributeMap map = new AttributeMap();
        for (Attribute attribute : Attribute.ALL) {
            map.instances.put(attribute, new AttributeInstance(attribute, attribute.playerBase()));
        }
        return map;
    }

    public AttributeInstance get(Attribute attribute) {
        return instances.get(attribute);
    }

    public double value(Attribute attribute) {
        return instances.get(attribute).value();
    }

    /**
     * {@code AttributeMap.assignAllValues(other)} (respawn keeping attributes): every instance of {@code other} replaces
     * this map's. Vanilla visits only instances {@code other} created; an instance never created still has its default
     * base and no modifiers, so replacing from it changes nothing.
     */
    public void assignAllValues(AttributeMap other) {
        other.instances.forEach((attribute, instance) -> instances.get(attribute).replaceFrom(instance));
    }

    /** {@code AttributeMap.assignBaseValues(other)}: only the base values, through {@code setBaseValue}. */
    public void assignBaseValues(AttributeMap other) {
        other.instances.forEach((attribute, instance) -> instances.get(attribute).setBaseValue(instance.baseValue()));
    }

    public AttributeMap copy() {
        AttributeMap copy = new AttributeMap();
        instances.forEach((attribute, instance) -> copy.instances.put(attribute, instance.copy()));
        return copy;
    }
}
