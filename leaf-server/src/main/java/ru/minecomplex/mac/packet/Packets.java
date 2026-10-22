package ru.minecomplex.mac.packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;



import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import ru.minecomplex.mac.util.Tri;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.world.entity.animal.sniffer.Sniffer.State;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.Relative;
import net.minecraft.core.Direction;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.VecDelta;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket.Pos;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket.PosRot;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket.Rot;
import ru.minecomplex.mac.player.connection.PlayerConnectionContext;

/**
 * How the core reads native 26.3 wrappers where a value needs more than a getter: angles as the client unpacks them,
 * enum mappings to vanilla's and synced data values.
 */
public final class Packets {
    private Packets() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static boolean relative(int mask, Relative flag) { return Relative.unpack(mask).contains(flag); }
    public static ServerboundPlayerAbilitiesPacket abilities(boolean flying) {
        var abilities = new net.minecraft.world.entity.player.Abilities(); abilities.flying = flying;
        return new ServerboundPlayerAbilitiesPacket(abilities);
    }
    public static ServerboundMoveVehiclePacket vehicleMove(net.minecraft.world.phys.Vec3 pos, float yaw, float pitch, boolean ground) {
        return new ServerboundMoveVehiclePacket(net.minecraft.core.PositionAndRotation.of(pos, yaw, pitch), ground);
    }

    /** Inverse of the native packet getter's Mth.unpackDegrees, including signed bytes. */
    public static byte packedAngle(float degrees) {
        return net.minecraft.util.Mth.packDegrees(degrees);
    }

    /** The angle the 26.3 client reads from a packed byte. */
    public static float unpackedAngle(byte packed) {
        return net.minecraft.util.Mth.unpackDegrees(packed);
    }

    /**
     * The position delta of {@code move_entity_pos}/{@code _pos_rot} (linear, or a 26.3 stepped one), {@code null} for
     * {@code move_entity_rot}.
     */
    public static VecDelta delta(Object move) {
        return switch (move) {
            case ClientboundMoveEntityPacket.PosRot p -> p.getPositionDelta();
            case ClientboundMoveEntityPacket.Pos p -> p.getPositionDelta();
            case ClientboundMoveEntityPacket.Rot ignored -> null;
            default -> throw new IllegalArgumentException("Not an entity move: " + move);
        };
    }

    /** Whether a {@code move_entity_*} carries a rotation. */
    public static boolean rotates(Object move) {
        return move instanceof ClientboundMoveEntityPacket.PosRot || move instanceof ClientboundMoveEntityPacket.Rot;
    }

    /** The packed yaw of a {@code move_entity_*} as on the wire (0 without a rotation). */
    public static byte packedYaw(Object move) {
        return switch (move) {
            case ClientboundMoveEntityPacket.PosRot p -> packedAngle(p.getYRot());
            case ClientboundMoveEntityPacket.Rot p -> packedAngle(p.getYRot());
            default -> 0;
        };
    }

    /** The packed pitch of a {@code move_entity_*} as on the wire (0 without a rotation). */
    public static byte packedPitch(Object move) {
        return switch (move) {
            case ClientboundMoveEntityPacket.PosRot p -> packedAngle(p.getXRot());
            case ClientboundMoveEntityPacket.Rot p -> packedAngle(p.getXRot());
            default -> 0;
        };
    }

    /** The ground flag of a {@code move_entity_*}. */
    public static boolean onGround(Object move) {
        return switch (move) {
            case ClientboundMoveEntityPacket.PosRot p -> p.isOnGround();
            case ClientboundMoveEntityPacket.Pos p -> p.isOnGround();
            case ClientboundMoveEntityPacket.Rot p -> p.isOnGround();
            default -> throw new IllegalArgumentException("Not an entity move: " + move);
        };
    }

    /** The entity a {@code move_entity_*} names. */
    public static int entityId(Object move) {
        return switch (move) {
            case ClientboundMoveEntityPacket.PosRot p -> p.entityId();
            case ClientboundMoveEntityPacket.Pos p -> p.entityId();
            case ClientboundMoveEntityPacket.Rot p -> p.entityId();
            default -> throw new IllegalArgumentException("Not an entity move: " + move);
        };
    }

    /** Whether a packet is a {@code move_entity_*}. */
    public static boolean entityMove(Object packet) {
        return packet instanceof ClientboundMoveEntityPacket.Pos || packet instanceof ClientboundMoveEntityPacket.PosRot
                || packet instanceof ClientboundMoveEntityPacket.Rot;
    }

    /**
     * Whether a {@code player_info_update} changes a {@code PlayerInfo} the model reads: it adds profiles or updates game
     * modes of at least one entry.
     */
    public static boolean playerInfoModelled(net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket packet) {
        var actions = packet.actions();
        return (actions.contains(net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)
                || actions.contains(net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE))
                && !packet.entries().isEmpty();
    }

    /** {@code EntityEvent.FINISHED_USING_ITEM} of {@code entity_event}. */
    public static final int FINISHED_USING_ITEM = 9;

    /** {@code game_event} ids (native {@code Reason} constants are declared in vanilla id order). */
    public static final ClientboundGameEventPacket.Type CHANGE_GAME_MODE = ClientboundGameEventPacket.CHANGE_GAME_MODE;

    /**
     * Whether a {@code player_info_update} adds or updates the game mode of the local player's own entry ({@code self}).
     */
    public static boolean concernsOwnEntry(net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket packet,
                                           java.util.UUID self) {
        var actions = packet.actions();
        if (!actions.contains(net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)
                && !actions.contains(net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE)) return false;
        for (var entry : packet.entries()) if (self.equals(entry.profileId())) return true;
        return false;
    }

    /** Whether a packet changes the world border ({@code initialize_border}, {@code set_border_*}). */
    public static boolean border(Object packet) {
        return packet instanceof net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
    }

    /** Whether every step of a position delta is zero (the move names where the entity is). */
    public static boolean zeroDelta(VecDelta delta) {
        if (delta instanceof net.minecraft.network.protocol.game.VecDelta.Linear linear) {
            return linear.xa() == 0 && linear.ya() == 0 && linear.za() == 0;
        }
        for (var step : ((net.minecraft.network.protocol.game.VecDelta.Stepped) delta).steps()) {
            if (step.xa() != 0 || step.ya() != 0 || step.za() != 0) return false;
        }
        return true;
    }

    /** The registry key of an {@code add_entity}'s type. */
    public static String type(net.minecraft.network.protocol.game.ClientboundAddEntityPacket spawn) {
        return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(spawn.getType()).toString();
    }

    /**
     * Whether two serverbound {@code move_vehicle} packets carry the same values, bit for bit as the client's floats and
     * doubles ({@code Double.compare}, {@code Float.compare}). {@code a} must not be {@code null}.
     */
    public static boolean same(net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket a,
                               net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket b) {
        if (b == null) return false;
        var pa = a.movingTo().position();
        var pb = b.movingTo().position();
        return Double.compare(pa.x(), pb.x()) == 0 && Double.compare(pa.y(), pb.y()) == 0
                && Double.compare(pa.z(), pb.z()) == 0 && Float.compare(a.movingTo().yRot(), b.movingTo().yRot()) == 0
                && Float.compare(a.movingTo().xRot(), b.movingTo().xRot()) == 0 && a.onGround() == b.onGround();
    }

    /** Whether two {@code paddle_boat} packets carry the same paddles. {@code a} must not be {@code null}. */
    public static boolean same(net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket a,
                               net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket b) {
        return b != null && a.getLeft() == b.getLeft() && a.getRight() == b.getRight();
    }

    /** Which fields a {@code move_player_*} packet carries. */
    public static MoveKind kind(ServerboundMovePlayerPacket packet) {
        return MoveKind.of(packet.hasPosition(), packet.hasRotation());
    }

    /**
     * A {@code move_player_*} the client sends: the fields its kind does not carry are 0, as native 26.3 decodes them.
     */
    public static ServerboundMovePlayerPacket movement(MoveKind kind, double x, double y, double z, float yRot, float xRot,
                                                         boolean onGround, boolean horizontalCollision) {
        if (kind.hasPosition() && kind.hasRotation()) return new ServerboundMovePlayerPacket.PosRot(x, y, z, yRot, xRot, onGround, horizontalCollision);
        if (kind.hasPosition()) return new ServerboundMovePlayerPacket.Pos(x, y, z, onGround, horizontalCollision);
        if (kind.hasRotation()) return new ServerboundMovePlayerPacket.Rot(yRot, xRot, onGround, horizontalCollision);
        return new ServerboundMovePlayerPacket.StatusOnly(onGround, horizontalCollision);
    }

    /** A {@code player_input} with these key flags ({@link ru.minecomplex.mac.player.state.PlayerInput#mask} bits). */
    public static ServerboundPlayerInputPacket input(int flags) {
        var input = ru.minecomplex.mac.player.state.PlayerInput.fromMask(flags);
        return new ServerboundPlayerInputPacket(new net.minecraft.world.entity.player.Input(input.forward(), input.backward(), input.left(), input.right(), input.jump(), input.shift(), input.sprint()));
    }

    /** A {@code player_command} the client sends (its entity id is not read). */
    public static ServerboundPlayerCommandPacket command(ServerboundPlayerCommandPacket.Action action, int data) {
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            buffer.writeVarInt(0);
            buffer.writeEnum(action);
            buffer.writeVarInt(data);
            return ServerboundPlayerCommandPacket.STREAM_CODEC.decode(buffer);
        } finally { buffer.release(); }
    }

    /** Whether a serverbound packet is sent from the client's netty thread ({@code keep_alive}, {@code chunk_batch_received}). */
    public static boolean nettyThread(Object packet) {
        return packet instanceof net.minecraft.network.protocol.common.ServerboundKeepAlivePacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
    }

    /**
     * Whether a vanilla client may send a serverbound packet from its input handling (screen callbacks while it polls window
     * events), between one render frame's ticks and the next frame's packet drain: container clicks and closes, creative
     * slots, chat and the other screen packets. They precede the drain whose pongs come after them, so a pong after them
     * is not a response inside a tick. Packets only {@code Minecraft.tick()} produces return false.
     */
    public static boolean betweenTicks(Object packet) {
        return packet instanceof net.minecraft.network.protocol.game.ServerboundContainerClickPacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundContainerClosePacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket
                || packet instanceof UnhandledPacket unhandled && !unhandled.clientbound();
    }

    /** {@code player_input}'s key flags ({@link ru.minecomplex.mac.player.state.PlayerInput#mask} bits). */
    public static int inputFlags(ServerboundPlayerInputPacket packet) {
        return new ru.minecomplex.mac.player.state.PlayerInput(packet.input().forward(), packet.input().backward(), packet.input().left(),
                packet.input().right(), packet.input().jump(), packet.input().shift(), packet.input().sprint()).mask();
    }

    /** {@code GameType.byId} semantics: native 26.3 already maps unknown ids to survival. */
    public static int gameModeId(GameType mode) {
        return mode == null ? 0 : mode.getId();
    }

    /**
     * The dimension-type part of {@code Level.canHaveWeather} of a {@code login}/{@code respawn}; the End-key exception
     * is applied by {@code CompensatedWorld.raining}.
     */
    public static Tri canHaveWeather(Object packet, DimensionType dimension) {
        return Tri.of(dimension.hasSkyLight() && !dimension.hasCeiling());
    }

    /** The local player's profile name a {@code login} was handled with. */
    public static String localName(Object login, PlayerConnectionContext facts) {
        return facts.localName();
    }

    /**
     * The mob items of {@code set_entity_data} by id: items from id 15 on with a byte, boolean, int, long or float value,
     * a sniffer's state or a shulker's attach face by their wire id (native order), and every optional int item (a
     * firework rocket's attachment, native 26.3 reads the wire's id + 1) whatever its id. The core knows the entity's
     * class and the client's numbering.
     */
    public static Map<Integer, Object> mobItems(ClientboundSetEntityDataPacket packet) {
        var mobItems = new TreeMap<Integer, Object>();
        for (var item : packet.packedItems()) {
            Object value = item.value();
            if (item.id() >= 15 && (value instanceof Byte || value instanceof Boolean || value instanceof Integer
                    || value instanceof Long || value instanceof Float)) {
                mobItems.put(item.id(), value);
            } else if (item.id() >= 15 && (value instanceof State || value instanceof Direction)) {
                mobItems.put(item.id(), ((Enum<?>) value).ordinal());
            } else if (item.serializer() == EntityDataSerializers.OPTIONAL_UNSIGNED_INT && value instanceof OptionalInt optional) {
                mobItems.put(item.id(), optional);
            }
        }
        return mobItems;
    }
}
