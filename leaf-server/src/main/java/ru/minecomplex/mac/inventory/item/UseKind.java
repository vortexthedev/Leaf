package ru.minecomplex.mac.inventory.item;

/**
 * Which {@code Item.use} an item runs (the class declaring the override, from the release's item registry): the
 * component-driven base ({@code consumable}, {@code equippable} swap, {@code blocks_attacks}, {@code kinetic_weapon}),
 * one of the classes that start using, or an override that never starts using, grouped by what it does on the client
 * (bytecode audit of native 26.3 overrides).
 */
public enum UseKind {
    BASE,
    BOW,
    CROSSBOW,
    TRIDENT,
    SPYGLASS,
    INSTRUMENT,
    BUNDLE,
    ENDER_EYE,
    /**
     * Eggs, snowballs, ender pearls, bottles o' enchanting, splash and lingering potions, wind charges: one item is
     * consumed on the client too ({@code ItemStack.consume}), success.
     */
    THROWABLE,
    /** Succeeds without changing the stack on the client: empty maps, fishing rods (the hook is the server's), books. */
    SUCCEED,
    /** {@code FireworkRocketItem}: succeeds while fall flying (only the server consumes it), else passes. */
    FIREWORK,
    /** {@code KnowledgeBookItem}: one item consumed; fails without the {@code recipes} component's recipes. */
    KNOWLEDGE_BOOK,
    /** The client's aim decides, the stack does not change on the client: boats, spawn eggs. */
    AIMED,
    /** The client's aim decides and the stack (and the world) may change: glass bottles, buckets, blocks placed on water. */
    AIMED_TRANSFORM,
    /** {@code FoodOnAStickItem}: passes on the client. */
    CLIENT_PASS;

    /** MAIB/MAIT format IDs are independent of Java enum ordinals. ID 4 is invalid for native 26.3 data. */
    public static UseKind fromDataId(int id) {
        return switch (id) {
            case 0 -> BASE;
            case 1 -> BOW;
            case 2 -> CROSSBOW;
            case 3 -> TRIDENT;
            case 5 -> SPYGLASS;
            case 6 -> INSTRUMENT;
            case 7 -> BUNDLE;
            case 8 -> ENDER_EYE;
            case 9 -> THROWABLE;
            case 10 -> SUCCEED;
            case 11 -> FIREWORK;
            case 12 -> KNOWLEDGE_BOOK;
            case 13 -> AIMED;
            case 14 -> AIMED_TRANSFORM;
            case 15 -> CLIENT_PASS;
            default -> throw new IllegalArgumentException("Unknown native item use ID: " + id);
        };
    }
}
