package ru.minecomplex.mac.player.transaction;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;



import java.util.UUID;
import ru.minecomplex.mac.entity.EntityDataItems;
import ru.minecomplex.mac.packet.Packets;

/**
 * Which clientbound changes deserve a marker right after them ({@code transactions.md} §3), so that each ends up
 * alone in its window instead of sharing one server tick's window with bulk state. Correctness never depends on
 * this; only how many hypotheses a window produces.
 */
public final class MarkerPolicy {
    private MarkerPolicy() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /** Grim's block-update transaction cooldown, measured on the monotonic wire clock. */
    public static final long BLOCK_UPDATE_INTERVAL_NANOS = 2_000_000L;

    /** High-volume block handlers share a cooldown; ordinary block/section updates use tick markers already. */
    public static boolean blockUpdate(Object packet) {
        return packet instanceof ClientboundBlockEventPacket
                || packet instanceof ClientboundBlockEntityDataPacket
                || packet instanceof ClientboundBlockChangedAckPacket;
    }

    /**
     * @param playerEntityId the client's own entity id (native), {@code Integer.MIN_VALUE} before login
     */
    /**
     * Firework rockets: one attached to the player pushes it in each client tick while it glides, from the tick after
     * the client handled its data until the one after its removal; a marker after its {@code set_entity_data} and after
     * its {@code remove_entities} tells those ticks.
     */
    public static boolean rocket(String type) {
        return type.equals("minecraft:firework_rocket");
    }

    /** Boats, rafts, happy ghasts and shulkers: the entities a player collides with. */
    public static boolean collidable(String type) {
        return type.endsWith("_boat") || type.endsWith("_raft") || type.equals("minecraft:happy_ghast") || type.equals("minecraft:shulker");
    }

    /**
     * A move, teleport or position sync of an entity the player collides with: a marker after it tells which client tick
     * moved the entity the player may stand on.
     */
    public static boolean motionMarker(Object packet, java.util.function.IntPredicate collidable) {
        return switch (packet) {
            case ClientboundTeleportEntityPacket teleport -> collidable.test(teleport.id());
            case ClientboundEntityPositionSyncPacket sync -> collidable.test(sync.id());
            // Ridden/collidable metadata can change the controller, boost clock or collision body independently
            // of a position update. A dedicated marker bounds those handler phases too.
            case ClientboundSetEntityDataPacket data -> !EntityDataItems.of(data).isEmpty() && collidable.test(data.id());
            default -> Packets.entityMove(packet) && collidable.test(Packets.entityId(packet));
        };
    }

    /**
     * @param playerEntityId the client's own entity id (native), {@code Integer.MIN_VALUE} before login
     * @param self           the local player's profile id
     */
    public static boolean markerAfter(Object packet, int playerEntityId, UUID self) {
        return switch (packet) {
            case ClientboundPlayerPositionPacket ignored -> true;
            case ClientboundPlayerRotationPacket ignored -> true;
            case ClientboundSetEntityMotionPacket velocity -> velocity.id() == playerEntityId;
            case ClientboundTeleportEntityPacket teleport -> teleport.id() == playerEntityId;
            case ClientboundExplodePacket explosion -> explosion.playerKnockback().isPresent();
            case ClientboundPlayerAbilitiesPacket ignored -> true;
            case ClientboundBlockEventPacket ignored -> true;
            case ClientboundBlockEntityDataPacket ignored -> true;
            // ends client block predictions: may set blocks back and move the player
            case ClientboundBlockChangedAckPacket ignored -> true;
            case ClientboundSetPassengersPacket ignored -> true;
            case ClientboundMoveVehiclePacket ignored -> true;
            case ClientboundInitializeBorderPacket ignored -> true;
            case ClientboundSetBorderCenterPacket ignored -> true;
            case ClientboundSetBorderSizePacket ignored -> true;
            case ClientboundSetBorderLerpSizePacket ignored -> true;
            // Riptide reads weather before its movement tick; settle the received rain state promptly.
            case ClientboundGameEventPacket game -> game.getEvent() == ClientboundGameEventPacket.CHANGE_GAME_MODE
                    || ru.minecomplex.mac.replica.CompensatedWorld.rainEvent(game);
            case ClientboundUpdateMobEffectPacket effect -> effect.getEntityId() == playerEntityId;
            case ClientboundRemoveMobEffectPacket effect -> effect.entityId() == playerEntityId;
            case ClientboundUpdateAttributesPacket attributes -> attributes.getEntityId() == playerEntityId;
            case ClientboundSetEntityDataPacket data -> data.id() == playerEntityId && !EntityDataItems.of(data).isEmpty();
            // a wake-up moves the player
            case ClientboundAnimatePacket animation -> animation.getId() == playerEntityId;
            case ClientboundContainerSetContentPacket ignored -> true;
            case ClientboundContainerSetSlotPacket ignored -> true;
            case ClientboundSetPlayerInventoryPacket ignored -> true;
            case ClientboundSetHeldSlotPacket ignored -> true;
            case ClientboundOpenScreenPacket ignored -> true;
            case ClientboundMountScreenOpenPacket ignored -> true;
            case ClientboundEntityEventPacket entityEvent -> entityEvent.entityId() == playerEntityId
                    && entityEvent.getEventId() == Packets.FINISHED_USING_ITEM;
            case ClientboundCooldownPacket ignored -> true;
            case ClientboundContainerClosePacket ignored -> true;
            case ClientboundSetHealthPacket ignored -> true;
            case ClientboundPlayerInfoUpdatePacket info -> Packets.concernsOwnEntry(info, self);
            case ClientboundPlayerInfoRemovePacket remove -> remove.profileIds().contains(self);
            case ClientboundSetPlayerTeamPacket ignored -> true;
            // the block tags decide climbing, fluids, suffocation and the heightmap
            case ClientboundUpdateTagsPacket ignored -> true;
            case ClientboundLoginPacket ignored -> true;
            case ClientboundRespawnPacket ignored -> true;
            case ClientboundChunkBatchFinishedPacket ignored -> true;
            default -> false;
        };
    }
}
