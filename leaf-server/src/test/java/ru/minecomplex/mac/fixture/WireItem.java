package ru.minecomplex.mac.fixture;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * An item stack as the wire carries it ({@code ItemStack.OPTIONAL_STREAM_CODEC}): item key, count (0 = empty) and the
 * component patch over the item's prototype, restricted to the components the model reads. A component is either
 * untouched, set ({@link #set}, value in {@link #values}) or removed ({@link #removed}); bits as in {@link #GLIDER} …
 * {@link #USE_REMAINDER}, and {@link #TOOL} without a value.
 */
public record WireItem(String item, int count, int set, int removed, ItemComponentSample values) {
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
    /** {@code tool}: only whether the stack sets or removes it ({@link ItemComponentSample#toolPatch}); its rules are not carried. */
    public static final int TOOL = 1 << 15;

    public static final WireItem EMPTY = new WireItem("minecraft:air", 0, 0, 0, ItemComponentSample.NONE);

    public WireItem {
        if ((set & removed) != 0) {
            throw new IllegalArgumentException("A component cannot be both set and removed: " + set + "/" + removed);
        }
    }

    /** An unpatched stack of {@code count} items. */
    public static WireItem of(String item, int count) {
        return new WireItem(item, count, 0, 0, ItemComponentSample.NONE);
    }

    /**
     * A patch of the original seven components (tests and older fixtures): values only for the set bits; a set
     * equippable is swappable and allows players.
     */
    public static WireItem of(String item, int count, int set, int removed, String equippableSlot, int maxDamage, int damage,
                              ItemComponentSample.UseEffects useEffects, float consumeSeconds) {
        boolean equippable = (set & EQUIPPABLE) != 0;
        return new WireItem(item, count, set, removed, new ItemComponentSample((set & GLIDER) != 0, (set & UNBREAKABLE) != 0,
                equippable ? equippableSlot : null, equippable, equippable, (set & MAX_DAMAGE) != 0 ? maxDamage : null,
                (set & DAMAGE) != 0 ? damage : null, (set & USE_EFFECTS) != 0 ? useEffects : null,
                (set & CONSUMABLE) != 0 ? consumeSeconds : null, null, false, false, false, null, null, null, null));
    }

    public boolean isEmpty() {
        return count <= 0 || item.equals("minecraft:air");
    }

    /** Resolved components: the prototype values of {@code defaults} with this patch applied. */
    public ItemComponentSample resolve(ItemComponentSample defaults) {
        boolean equippable = has(EQUIPPABLE, defaults.equippableSlot() != null);
        ItemComponentSample source = (set & EQUIPPABLE) != 0 ? values : defaults;
        return new ItemComponentSample(
                has(GLIDER, defaults.glider()),
                has(UNBREAKABLE, defaults.unbreakable()),
                equippable ? source.equippableSlot() : null,
                equippable && source.swappable(),
                equippable && source.equippableByPlayer(),
                pick(MAX_DAMAGE, values.maxDamage(), defaults.maxDamage()),
                pick(DAMAGE, values.damage(), defaults.damage()),
                pick(USE_EFFECTS, values.useEffects(), defaults.useEffects()),
                pick(CONSUMABLE, values.consumeSeconds(), defaults.consumeSeconds()),
                pick(FOOD, values.food(), defaults.food()),
                has(BLOCKS_ATTACKS, defaults.blocksAttacks()),
                has(KINETIC_WEAPON, defaults.kineticWeapon()),
                (set & CHARGED_PROJECTILES) != 0 ? values.charged() : (removed & CHARGED_PROJECTILES) == 0 && defaults.charged(),
                pick(INSTRUMENT, values.instrumentSeconds(), defaults.instrumentSeconds()),
                pick(USE_COOLDOWN, values.useCooldown(), defaults.useCooldown()),
                pick(ENCHANTMENTS, values.enchantments(), defaults.enchantments()),
                pick(USE_REMAINDER, values.useRemainder(), defaults.useRemainder()),
                (set & TOOL) != 0 ? ItemComponentSample.TOOL_SET : (removed & TOOL) != 0 ? ItemComponentSample.TOOL_REMOVED : ItemComponentSample.TOOL_PROTOTYPE);
    }

    private boolean has(int bit, boolean fallback) {
        return (set & bit) != 0 || (removed & bit) == 0 && fallback;
    }

    private <T> T pick(int bit, T value, T fallback) {
        return (set & bit) != 0 ? value : (removed & bit) != 0 ? null : fallback;
    }

    /** Flat payload of a stack list, for records. */
    public static byte[] encode(List<WireItem> items) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(items.size());
            for (WireItem item : items) {
                out.writeUTF(item.item);
                out.writeInt(item.count);
                out.writeInt(item.set);
                out.writeInt(item.removed);
                ItemSampleCodec.write(out, item.values, item.set);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    public static byte[] encode(WireItem item) {
        return encode(List.of(item));
    }

    public static List<WireItem> decode(byte[] payload) {
        try {
            var in = new DataInputStream(new ByteArrayInputStream(payload));
            int count = in.readInt();
            if (count < 0 || count > payload.length) throw new IOException("Invalid item count " + count);
            List<WireItem> items = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String key = in.readUTF();
                int stackCount = in.readInt();
                int set = in.readInt(), removed = in.readInt();
                items.add(new WireItem(key, stackCount, set, removed, ItemSampleCodec.read(in, set)));
            }
            if (in.read() != -1) throw new IOException("Trailing item payload");
            return List.copyOf(items);
        } catch (IOException malformed) {
            throw new UncheckedIOException(malformed);
        }
    }

    /** The single stack of a payload written by {@link #encode(WireItem)}. */
    public static WireItem decodeOne(byte[] payload) {
        List<WireItem> items = decode(payload);
        if (items.size() != 1) throw new IllegalArgumentException("Expected one item, got " + items.size());
        return items.getFirst();
    }
}
