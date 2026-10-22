package ru.minecomplex.mac.fixture;

/**
 * The data components of an item stack that the local-player model reads, resolved as vanilla does: the item's
 * prototype values (vanilla fixture data) with the stack's patch applied. {@code null} or {@code false} means the
 * component is absent; bits ({@link #GLIDER} …) name them.
 *
 * @param equippableSlot      {@code equippable.slot()} name ({@link ru.minecomplex.mac.inventory.EquipmentSlot}); {@code null} without the component
 * @param swappable           {@code equippable.swappable()} (right-click swaps it into its slot)
 * @param equippableByPlayer  {@code equippable.canBeEquippedBy(player)} ({@code allowed_entities} absent or with players)
 * @param useEffects          {@code use_effects}
 * @param consumeSeconds      {@code consumable.consumeSeconds()}
 * @param food                {@code food}
 * @param blocksAttacks       {@code blocks_attacks}: use starts blocking
 * @param kineticWeapon       {@code kinetic_weapon}: use starts the charge
 * @param charged             {@code charged_projectiles} not empty (a loaded crossbow)
 * @param instrumentSeconds   {@code instrument}: the instrument's use duration (vanilla goat horns 7), −1 when the
 *                            instrument is not resolved; {@code null} without the component
 * @param useCooldown         {@code use_cooldown}
 * @param enchantments        what the model reads of {@code enchantments}
 * @param useRemainder        {@code use_remainder}: the stack a finished use turns the item into
 * @param toolPatch           how the stack patches {@code tool} ({@link #TOOL_PROTOTYPE}, {@link #TOOL_REMOVED},
 *                            {@link #TOOL_SET}); the prototype's rule is vanilla fixture data, a set
 *                            value is not modelled
 */
public record ItemComponentSample(boolean glider, boolean unbreakable, String equippableSlot, boolean swappable,
                             boolean equippableByPlayer, Integer maxDamage, Integer damage, UseEffects useEffects,
                             Float consumeSeconds, Food food, boolean blocksAttacks, boolean kineticWeapon,
                             boolean charged, Float instrumentSeconds, UseCooldown useCooldown, Enchantments enchantments,
                             Remainder useRemainder, int toolPatch) {
    public static final int GLIDER = 1;
    public static final int UNBREAKABLE = 1 << 1;
    public static final int EQUIPPABLE = 1 << 2;
    public static final int MAX_DAMAGE = 1 << 3;
    public static final int DAMAGE = 1 << 4;
    public static final int USE_EFFECTS = 1 << 5;
    public static final int CONSUMABLE = 1 << 6;
    public static final int FOOD = 1 << 7;
    public static final int BLOCKS_ATTACKS = 1 << 8;
    public static final int KINETIC_WEAPON = 1 << 9;
    public static final int CHARGED_PROJECTILES = 1 << 10;
    public static final int INSTRUMENT = 1 << 11;
    public static final int USE_COOLDOWN = 1 << 12;
    public static final int ENCHANTMENTS = 1 << 13;
    public static final int USE_REMAINDER = 1 << 14;
    /** {@code tool}: only whether the stack sets or removes it ({@link #toolPatch}); its rules are not read. */
    public static final int TOOL = 1 << 15;

    /** The stack keeps the prototype's {@code tool}. */
    public static final int TOOL_PROTOTYPE = 0;
    /** The stack removes {@code tool}. */
    public static final int TOOL_REMOVED = 1;
    /** The stack sets its own {@code tool} (its rules are not modelled). */
    public static final int TOOL_SET = 2;

    public static final ItemComponentSample NONE = new ItemComponentSample(false, false, null, false, false, null, null, null, null,
            null, false, false, false, null, null, null, null);

    /** Components that keep the prototype's {@code tool}. */
    public ItemComponentSample(boolean glider, boolean unbreakable, String equippableSlot, boolean swappable,
                          boolean equippableByPlayer, Integer maxDamage, Integer damage, UseEffects useEffects,
                          Float consumeSeconds, Food food, boolean blocksAttacks, boolean kineticWeapon,
                          boolean charged, Float instrumentSeconds, UseCooldown useCooldown, Enchantments enchantments,
                          Remainder useRemainder) {
        this(glider, unbreakable, equippableSlot, swappable, equippableByPlayer, maxDamage, damage, useEffects, consumeSeconds,
                food, blocksAttacks, kineticWeapon, charged, instrumentSeconds, useCooldown, enchantments, useRemainder, TOOL_PROTOTYPE);
    }

    /** {@code UseEffects}: whether sprinting is kept while using the item, and the input speed multiplier. */
    public record UseEffects(boolean canSprint, boolean interactVibrations, float speedMultiplier) {
        /** {@code UseEffects.DEFAULT}, read for a used stack without the component. */
        public static final UseEffects DEFAULT = new UseEffects(false, true, 0.2F);
    }

    /** {@code FoodProperties}. */
    public record Food(int nutrition, float saturation, boolean canAlwaysEat) {
    }

    /** {@code UseCooldown}: seconds and the cooldown group ({@code null}: the item itself). */
    public record UseCooldown(float seconds, String group) {
    }

    /**
     * {@code ItemEnchantments} as the model reads them: a trident spin attack ({@code riptide}), the armor-change
     * prevention ({@code binding_curse}) and the level of {@code quick_charge} (0 without it), the vanilla enchantments
     * with these effects; and {@code EnchantmentHelper.getTridentSpinAttackStrength} of the stack as the server's
     * enchantment registry (which the client receives) computes it, {@code NaN} where it is not known.
     */
    public record Enchantments(boolean spinAttack, boolean preventArmorChange, int quickCharge, float spinStrength) {
        /** Without a known spin strength: none without riptide, not known with it. */
        public Enchantments(boolean spinAttack, boolean preventArmorChange, int quickCharge) {
            this(spinAttack, preventArmorChange, quickCharge, spinAttack ? Float.NaN : 0.0F);
        }
    }

    /** {@code UseRemainder}: item key and count. */
    public record Remainder(String item, int count) {
    }

    /** An equippable component with only a slot (swappable, any entity): the shape of the older data. */
    public static ItemComponentSample of(boolean glider, boolean unbreakable, String equippableSlot, Integer maxDamage,
                                    Integer damage, UseEffects useEffects, Float consumeSeconds) {
        return new ItemComponentSample(glider, unbreakable, equippableSlot, equippableSlot != null, equippableSlot != null,
                maxDamage, damage, useEffects, consumeSeconds, null, false, false, false, null, null, null, null);
    }
}
