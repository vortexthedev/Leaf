package ru.minecomplex.mac.entity;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;



import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import ru.minecomplex.mac.packet.Packets;
import ru.minecomplex.mac.world.BlockPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;

/**
 * The items of a {@code set_entity_data} the model reads, by data id, as the client's {@code SynchedEntityData} stores
 * them: the base entity / living-entity items (ids identical in all supported protocols) and the mob items
 * ({@link Packets#mobItems}). Base items are taken only with their value types: {@link #SHARED_FLAGS} a byte,
 * {@link #NO_GRAVITY} a boolean, {@link #POSE} a {@link Pose}, {@link #LIVING_FLAGS} a byte, {@link #HEALTH} a float,
 * {@link #SLEEPING_POS} an {@code Optional<BlockPosition>}.
 */
public final class EntityDataItems {
    private EntityDataItems() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static final int SHARED_FLAGS = 0;
    public static final int NO_GRAVITY = 5;
    public static final int POSE = 6;
    public static final int LIVING_FLAGS = 8;
    public static final int HEALTH = 9;
    public static final int SLEEPING_POS = 14;

    /** The items of {@code packet} the model reads, by data id ({@link TreeMap} order). */
    public static Map<Integer, Object> of(ClientboundSetEntityDataPacket packet) {
        var items = new TreeMap<Integer, Object>(Packets.mobItems(packet));
        for (var item : packet.packedItems()) {
            Object value = item.value();
            switch (item.id()) {
                case SHARED_FLAGS, LIVING_FLAGS -> { if (value instanceof Byte b) items.put(item.id(), b); }
                case NO_GRAVITY -> { if (value instanceof Boolean b) items.put(NO_GRAVITY, b); }
                case POSE -> { if (value instanceof Enum<?> e) items.put(POSE, Pose.valueOf(e.name())); }
                case HEALTH -> { if (value instanceof Float f) items.put(HEALTH, f); }
                case SLEEPING_POS -> {
                    if (value instanceof Optional<?> o) {
                        items.put(SLEEPING_POS, o.orElse(null) instanceof BlockPos bed
                                ? Optional.of(new BlockPosition(bed.getX(), bed.getY(), bed.getZ())) : Optional.empty());
                    }
                }
                default -> { }
            }
        }
        return items;
    }

    /** A mob item ({@link Packets#mobItems}), not a base one. */
    public static boolean mobItem(int id, Object value) {
        return id >= 15 || value instanceof OptionalInt;
    }
}
