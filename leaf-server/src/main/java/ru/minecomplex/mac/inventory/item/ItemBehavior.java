package ru.minecomplex.mac.inventory.item;


import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.packet.NativeRuntime;

/** Vanilla item implementation facts not supplied by native component registry (MAIB v1). */
public final class ItemBehavior {
    private static final int MAGIC = 0x4d414942;
    private static final int ARROW = 1;
    private static final int USE_ON_STARTS_USING = 2;
    private static final int BLOCK_ITEM_USE_ON = 4;

    private record Entry(UseKind use, int flags, String destroyRuleOverride) {
    }

    private static volatile ItemBehavior loaded;

    private final Map<String, Entry> items;

    private ItemBehavior(Map<String, Entry> items) {
        this.items = items;
    }

    public static ItemBehavior get() {
        ItemBehavior behavior = loaded;
        if (behavior != null) return behavior;
        synchronized (ItemBehavior.class) {
            if (loaded == null) loaded = load();
            return loaded;
        }
    }

    /** The {@code Item.use} the item runs. */
    public UseKind use(String item) {
        return entry(item).use();
    }

    /** In the vanilla {@code #minecraft:arrows} item tag (what bows and crossbows look for). */
    public boolean arrow(String item) {
        return (entry(item).flags() & ARROW) != 0;
    }

    /**
     * Its {@code useOn} is {@code BlockItem.useOn}: it places a block, and a stack with {@code consumable} whose placing
     * fails runs {@code Item.use} (eating may start).
     */
    public boolean blockItemUseOn(String item) {
        return (entry(item).flags() & BLOCK_ITEM_USE_ON) != 0;
    }

    /**
     * The simple name of the class overriding the rule {@code MultiPlayerGameMode.destroyBlock} asks the main-hand item
     * ({@code canDestroyBlock}, before 1.21.5 {@code canAttackBlock}), empty for {@code Item}'s own.
     */
    public String destroyRuleOverride(String item) {
        return entry(item).destroyRuleOverride();
    }

    /** Its {@code Item.useOn} may start using it ({@code BrushItem}). */
    public boolean startsUsingOnBlock(String item) {
        return (entry(item).flags() & USE_ON_STARTS_USING) != 0;
    }

    private Entry entry(String item) {
        Entry entry = items.get(item);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown item " + item);
        }
        return entry;
    }

    public int size() {
        return items.size();
    }

    private static ItemBehavior load() {
        String path = "/mac/item-behavior/" + NativeRuntime.PROTOCOL + ".bin.gz";
        try (InputStream raw = ItemBehavior.class.getResourceAsStream(path)) {
            if (raw == null) throw new IOException("Missing " + path);
            var in = new DataInputStream(new GZIPInputStream(raw));
            if (in.readInt() != MAGIC || in.readUnsignedShort() != 1 || in.readInt() != NativeRuntime.PROTOCOL) {
                throw new IOException("Unexpected item data header in " + path);
            }
            in.readUTF();
            int count = in.readInt();
            Map<String, Entry> items = new HashMap<>(count * 2);
            for (int i = 0; i < count; i++) {
                String key = in.readUTF();
                UseKind use = UseKind.fromDataId(in.readUnsignedByte());
                int flags = in.readUnsignedByte();
                String override = in.readUTF();
                items.put(key, new Entry(use, flags, override));
            }
            if (in.read() != -1) throw new IOException("Trailing item data in " + path);
            return new ItemBehavior(Map.copyOf(items));
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot load bundled vanilla data " + path, failure);
        }
    }
}
