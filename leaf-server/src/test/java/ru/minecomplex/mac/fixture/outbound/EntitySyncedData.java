package ru.minecomplex.mac.fixture.outbound;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code set_entity_data}: the base entity and living-entity items the local-player model reads. The client calls
 * {@code SynchedEntityData.assignValues}, which stores each value and runs {@code onSyncedDataUpdated} for it
 * ({@code ClientPacketListener.handleSetEntityData}). The server also sends a player's own dirty data to that player
 * ({@code ServerEntity.sendDirtyEntityData} → {@code sendToTrackingPlayersAndSelf}).
 *
 * <p>Data ids are identical in vanilla 26.3 ({@code EntityDataProbe}): 0 shared flags (byte), 5 no gravity
 * (boolean), 6 pose, 8 living-entity flags (byte), 9 health (float). Items 8 and 9 mean something else on non-living
 * entities; they are only interpreted for the client's own player.
 *
 * <p>{@link #mobItems()} carries the items from id 15 on whose value is a byte, boolean, int, long or float, and every
 * optional int item ({@code EntityDataSerializers.OPTIONAL_UNSIGNED_INT}: a firework rocket's
 * {@code DATA_ATTACHED_TO_TARGET}) as an {@link java.util.OptionalInt}, by id as the client's protocol numbers them; their
 * meaning depends on the entity's class  ({@code ru.minecomplex.mac.entity.MobData}).
 *
 * @param present  bit set of the items in the packet ({@link #SHARED_FLAGS}, …)
 * @param pose     vanilla {@code Pose} name
 * @param sleeping item 14 ({@code LivingEntity.SLEEPING_POS_ID}, {@link #SLEEPING_POS}): a sleeping position is present
 *                 ({@code isSleeping()}), at {@code sleepX/Y/Z}
 */
public record EntitySyncedData(int entityId, int present, byte sharedFlags, boolean noGravity, String pose,
                               byte livingFlags, float health, byte[] mobItems, boolean sleeping, int sleepX, int sleepY,
                               int sleepZ) implements OutboundEvent {
    public static final int SHARED_FLAGS = 1;
    public static final int NO_GRAVITY = 1 << 1;
    public static final int POSE = 1 << 2;
    public static final int LIVING_FLAGS = 1 << 3;
    public static final int HEALTH = 1 << 4;
    public static final int SLEEPING_POS = 1 << 5;

    private static final byte BYTE = 0, BOOLEAN = 1, INT = 2, LONG = 3, FLOAT = 4, OPTIONAL_INT = 5;

    public EntitySyncedData {
        mobItems = mobItems.clone();
    }

    /** Without a sleeping position item. */
    public EntitySyncedData(int entityId, int present, byte sharedFlags, boolean noGravity, String pose, byte livingFlags,
                            float health, byte[] mobItems) {
        this(entityId, present, sharedFlags, noGravity, pose, livingFlags, health, mobItems, false, 0, 0, 0);
    }

    /** Without mob items. */
    public EntitySyncedData(int entityId, int present, byte sharedFlags, boolean noGravity, String pose, byte livingFlags,
                            float health) {
        this(entityId, present, sharedFlags, noGravity, pose, livingFlags, health, new byte[0]);
    }

    public boolean has(int item) {
        return (present & item) != 0;
    }

    @Override
    public byte[] mobItems() {
        return mobItems.clone();
    }

    /**
     * Data id → value ({@code Byte}, {@code Boolean}, {@code Integer}, {@code Long}, {@code Float} or
     * {@code java.util.OptionalInt}), in id order.
     */
    public Map<Integer, Object> mobItemValues() {
        var values = new TreeMap<Integer, Object>();
        if (mobItems.length == 0) return values;
        try {
            var in = new DataInputStream(new ByteArrayInputStream(mobItems));
            for (int n = in.readInt(); n > 0; n--) {
                int id = in.readInt();
                values.put(id, switch (in.readByte()) {
                    case BYTE -> in.readByte();
                    case BOOLEAN -> in.readBoolean();
                    case INT -> in.readInt();
                    case LONG -> in.readLong();
                    case FLOAT -> in.readFloat();
                    case OPTIONAL_INT -> {
                        boolean present = in.readBoolean();
                        int value = in.readInt();
                        yield present ? java.util.OptionalInt.of(value) : java.util.OptionalInt.empty();
                    }
                    default -> throw new IOException("Unknown mob item kind");
                });
            }
            return values;
        } catch (IOException malformed) {
            throw new UncheckedIOException(malformed);
        }
    }

    /** The flat payload of {@link #mobItems()}; values of other types are not representable. */
    public static byte[] encodeMobItems(Map<Integer, Object> items) {
        if (items.isEmpty()) return new byte[0];
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            var sorted = new TreeMap<>(items);
            out.writeInt(sorted.size());
            for (var entry : sorted.entrySet()) {
                out.writeInt(entry.getKey());
                switch (entry.getValue()) {
                    case Byte b -> {
                        out.writeByte(BYTE);
                        out.writeByte(b);
                    }
                    case Boolean b -> {
                        out.writeByte(BOOLEAN);
                        out.writeBoolean(b);
                    }
                    case Integer i -> {
                        out.writeByte(INT);
                        out.writeInt(i);
                    }
                    case Long l -> {
                        out.writeByte(LONG);
                        out.writeLong(l);
                    }
                    case Float f -> {
                        out.writeByte(FLOAT);
                        out.writeFloat(f);
                    }
                    case java.util.OptionalInt o -> {
                        out.writeByte(OPTIONAL_INT);
                        out.writeBoolean(o.isPresent());
                        out.writeInt(o.orElse(0));
                    }
                    default -> throw new IllegalArgumentException("Not a mob item value: " + entry.getValue());
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EntitySyncedData that && entityId == that.entityId && present == that.present
                && sharedFlags == that.sharedFlags && noGravity == that.noGravity && java.util.Objects.equals(pose, that.pose)
                && livingFlags == that.livingFlags && Float.floatToIntBits(health) == Float.floatToIntBits(that.health)
                && Arrays.equals(mobItems, that.mobItems) && sleeping == that.sleeping && sleepX == that.sleepX
                && sleepY == that.sleepY && sleepZ == that.sleepZ;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(entityId, present, sharedFlags, noGravity, pose, livingFlags, health, sleeping, sleepX, sleepY, sleepZ) * 31
                + Arrays.hashCode(mobItems);
    }

    @Override
    public String toString() {
        return "EntitySyncedData[entityId=" + entityId + ", present=" + present + ", sharedFlags=" + sharedFlags
                + ", noGravity=" + noGravity + ", pose=" + pose + ", livingFlags=" + livingFlags + ", health=" + health
                + ", mobItems=" + mobItemValues() + ", sleeping=" + sleeping
                + (sleeping ? "@" + sleepX + "," + sleepY + "," + sleepZ : "") + "]";
    }
}
