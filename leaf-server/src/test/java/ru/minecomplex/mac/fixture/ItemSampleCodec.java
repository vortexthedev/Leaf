package ru.minecomplex.mac.fixture;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * The binary form of {@link ItemComponentSample} shared by item data (MAIT v3), wire patches and the MAPS oracle: an int of
 * component bits ({@link ItemComponentSample#GLIDER} …), then the values of the present components in bit order. Must stay equal
 * to {@code tools/datagen} {@code ItemDataSamples.writeComponents}.
 */
public final class ItemSampleCodec {
    private ItemSampleCodec() {
    }

    /** The values of the components in {@code bits}. */
    public static void write(DataOutput out, ItemComponentSample c, int bits) throws IOException {
        if ((bits & ItemComponentSample.EQUIPPABLE) != 0) {
            out.writeUTF(c.equippableSlot());
            out.writeBoolean(c.swappable());
            out.writeBoolean(c.equippableByPlayer());
        }
        if ((bits & ItemComponentSample.MAX_DAMAGE) != 0) out.writeInt(c.maxDamage());
        if ((bits & ItemComponentSample.DAMAGE) != 0) out.writeInt(c.damage());
        if ((bits & ItemComponentSample.USE_EFFECTS) != 0) {
            out.writeBoolean(c.useEffects().canSprint());
            out.writeBoolean(c.useEffects().interactVibrations());
            out.writeFloat(c.useEffects().speedMultiplier());
        }
        if ((bits & ItemComponentSample.CONSUMABLE) != 0) out.writeFloat(c.consumeSeconds());
        if ((bits & ItemComponentSample.FOOD) != 0) {
            out.writeInt(c.food().nutrition());
            out.writeFloat(c.food().saturation());
            out.writeBoolean(c.food().canAlwaysEat());
        }
        if ((bits & ItemComponentSample.CHARGED_PROJECTILES) != 0) out.writeBoolean(c.charged());
        if ((bits & ItemComponentSample.INSTRUMENT) != 0) out.writeFloat(c.instrumentSeconds());
        if ((bits & ItemComponentSample.USE_COOLDOWN) != 0) {
            out.writeFloat(c.useCooldown().seconds());
            out.writeBoolean(c.useCooldown().group() != null);
            if (c.useCooldown().group() != null) out.writeUTF(c.useCooldown().group());
        }
        if ((bits & ItemComponentSample.ENCHANTMENTS) != 0) {
            // bit 0: riptide; bit 1: the spin strength follows (payloads before it hold a plain boolean here)
            out.writeByte((c.enchantments().spinAttack() ? 1 : 0) | 2);
            out.writeBoolean(c.enchantments().preventArmorChange());
            out.writeInt(c.enchantments().quickCharge());
            out.writeFloat(c.enchantments().spinStrength());
        }
        if ((bits & ItemComponentSample.USE_REMAINDER) != 0) {
            out.writeUTF(c.useRemainder().item());
            out.writeInt(c.useRemainder().count());
        }
    }

    /** Components with the values of {@code bits} read and every other component absent. */
    public static ItemComponentSample read(DataInput in, int bits) throws IOException {
        String slot = null;
        boolean swappable = false, byPlayer = false;
        if ((bits & ItemComponentSample.EQUIPPABLE) != 0) {
            slot = in.readUTF();
            swappable = in.readBoolean();
            byPlayer = in.readBoolean();
        }
        Integer maxDamage = (bits & ItemComponentSample.MAX_DAMAGE) != 0 ? in.readInt() : null;
        Integer damage = (bits & ItemComponentSample.DAMAGE) != 0 ? in.readInt() : null;
        ItemComponentSample.UseEffects effects = (bits & ItemComponentSample.USE_EFFECTS) != 0
                ? new ItemComponentSample.UseEffects(in.readBoolean(), in.readBoolean(), in.readFloat()) : null;
        Float seconds = (bits & ItemComponentSample.CONSUMABLE) != 0 ? in.readFloat() : null;
        ItemComponentSample.Food food = (bits & ItemComponentSample.FOOD) != 0
                ? new ItemComponentSample.Food(in.readInt(), in.readFloat(), in.readBoolean()) : null;
        boolean charged = (bits & ItemComponentSample.CHARGED_PROJECTILES) != 0 && in.readBoolean();
        Float instrument = (bits & ItemComponentSample.INSTRUMENT) != 0 ? in.readFloat() : null;
        ItemComponentSample.UseCooldown cooldown = null;
        if ((bits & ItemComponentSample.USE_COOLDOWN) != 0) {
            float cooldownSeconds = in.readFloat();
            cooldown = new ItemComponentSample.UseCooldown(cooldownSeconds, in.readBoolean() ? in.readUTF() : null);
        }
        ItemComponentSample.Enchantments enchantments = null;
        if ((bits & ItemComponentSample.ENCHANTMENTS) != 0) {
            int flags = in.readUnsignedByte();
            boolean binding = in.readBoolean();
            int quickCharge = in.readInt();
            enchantments = (flags & 2) != 0
                    ? new ItemComponentSample.Enchantments((flags & 1) != 0, binding, quickCharge, in.readFloat())
                    : new ItemComponentSample.Enchantments((flags & 1) != 0, binding, quickCharge);
        }
        ItemComponentSample.Remainder remainder = (bits & ItemComponentSample.USE_REMAINDER) != 0
                ? new ItemComponentSample.Remainder(in.readUTF(), in.readInt()) : null;
        return new ItemComponentSample((bits & ItemComponentSample.GLIDER) != 0, (bits & ItemComponentSample.UNBREAKABLE) != 0, slot, swappable,
                byPlayer, maxDamage, damage, effects, seconds, food, (bits & ItemComponentSample.BLOCKS_ATTACKS) != 0,
                (bits & ItemComponentSample.KINETIC_WEAPON) != 0, charged, instrument, cooldown,
                enchantments, remainder);
    }

    /** The bits of the components present in {@code c}. */
    public static int present(ItemComponentSample c) {
        return (c.glider() ? ItemComponentSample.GLIDER : 0) | (c.unbreakable() ? ItemComponentSample.UNBREAKABLE : 0)
                | (c.equippableSlot() != null ? ItemComponentSample.EQUIPPABLE : 0) | (c.maxDamage() != null ? ItemComponentSample.MAX_DAMAGE : 0)
                | (c.damage() != null ? ItemComponentSample.DAMAGE : 0) | (c.useEffects() != null ? ItemComponentSample.USE_EFFECTS : 0)
                | (c.consumeSeconds() != null ? ItemComponentSample.CONSUMABLE : 0) | (c.food() != null ? ItemComponentSample.FOOD : 0)
                | (c.blocksAttacks() ? ItemComponentSample.BLOCKS_ATTACKS : 0) | (c.kineticWeapon() ? ItemComponentSample.KINETIC_WEAPON : 0)
                | (c.charged() ? ItemComponentSample.CHARGED_PROJECTILES : 0) | (c.instrumentSeconds() != null ? ItemComponentSample.INSTRUMENT : 0)
                | (c.useCooldown() != null ? ItemComponentSample.USE_COOLDOWN : 0) | (c.enchantments() != null ? ItemComponentSample.ENCHANTMENTS : 0)
                | (c.useRemainder() != null ? ItemComponentSample.USE_REMAINDER : 0);
    }
}
