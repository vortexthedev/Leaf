package ru.minecomplex.mac.inventory;

/** Vanilla {@code EquipmentSlot} names; a player has items only in the hand and humanoid armor slots. */
public enum EquipmentSlot {
    MAINHAND,
    OFFHAND,
    FEET,
    LEGS,
    CHEST,
    HEAD,
    BODY,
    SADDLE;

    /** The slot named {@code name}, or {@code null} for a name this model does not know. */
    public static EquipmentSlot byName(String name) {
        for (EquipmentSlot slot : values()) {
            if (slot.name().equals(name)) {
                return slot;
            }
        }
        return null;
    }
}
