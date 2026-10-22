package ru.minecomplex.mac.player;

import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;
import net.minecraft.world.level.GameType;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import java.util.EnumSet;
import java.util.Set;
import ru.minecomplex.mac.entity.Pose;
import ru.minecomplex.mac.replica.CompensatedInventory;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.mechanics.movement.RotationUpdate;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.listener.ClientPlayerHandlers;
import ru.minecomplex.mac.listener.TickActions;
import ru.minecomplex.mac.player.state.ClientPlayerData;

/**
 * The new {@code LocalPlayer} that {@code ClientPacketListener.handleLogin} and {@code handleRespawn} create, in
 * handler order ({@code docs/research/architecture.md} §2). The new player's position is the constructor's (the shared
 * spawn position in 1.21.4) moved up by {@code resetPos} until it does not collide; that depends on unmodelled level
 * data and is reported as {@link Unmodeled#RESPAWN_POSITION} until an absolute teleport sets it.
 *
 * <p>Client-global state survives: the fluid shape cache ({@code FlowingFluid}'s static cache) and the key mappings
 * ({@code Options}) behind {@link LocalPlayerState#controls}.
 */
public final class PlayerRecreation {
    private PlayerRecreation() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    /**
     * {@code LivingEntity}: {@code builder.define(DATA_HEALTH_ID, 1.0F)}; a value equal to it is not copied.
     */
    private static final float HEALTH_DATA_DEFAULT = 1.0F;

    /**
     * {@code Attributes.MAX_HEALTH} default: the new player's maximum health until attributes are assigned.
     */
    private static final float NEW_PLAYER_MAX_HEALTH = 20.0F;

    public static record Recreated(PlayerState player, LocalPlayerState local, Set<Unmodeled> gaps, Set<Unmodeled> resolves) {
    }

    /**
     * {@code handleRespawn}: {@code createPlayer} (the constructor stores the send state it is given), entity data,
     * motion and rotation when {@code KEEP_ENTITY_DATA} is kept, otherwise {@code resetPos()} and
     * {@code setYRot(-180)}; attributes; a new {@code KeyboardInput}; {@code adjustPlayer} with the current local mode;
     * {@code setLocalMode(spawn game type)}.
     */
    public static Recreated respawn(PlayerState old, LocalPlayerState oldLocal, ClientPlayerData data, ClientboundRespawnPacket respawn) {
        boolean keepData = (respawn.dataToKeep() & ClientboundRespawnPacket.KEEP_ENTITY_DATA) != 0;
        PlayerState player = newPlayer(old);
        LocalPlayerState local = newLocal(oldLocal);
        Set<Unmodeled> resolves = EnumSet.of(Unmodeled.PLAYER_INFO_IDENTITY, Unmodeled.INVENTORY_CONTAINER, Unmodeled.DROP_SLOT, Unmodeled.INVENTORY_CLIENT_ACTION, Unmodeled.ITEM_USE_TIMING, Unmodeled.ITEM_USE_OUTCOME, Unmodeled.RIPTIDE);
        if (keepData) {
            {
                local.send.lastSentInput = oldLocal.send.lastSentInput;
            }
            local.send.wasSprinting = old.sprinting;
            // assignValues(getNonDefaultValues()): items still at their defined default are not copied; for every
            // modelled item but health the default equals the new player's value
            player.sprinting = old.sprinting;
            player.swimming = old.swimming;
            player.fallFlying = old.fallFlying;
            player.pose = old.pose;
            player.autoSpinAttack = old.autoSpinAttack;
            player.noGravity = old.noGravity;
            if (Float.floatToIntBits(data.health) == Float.floatToIntBits(HEALTH_DATA_DEFAULT)) {
                data.health = NEW_PLAYER_MAX_HEALTH;
            }
            player.deltaMovement = old.deltaMovement;
            player.yRot = RotationUpdate.storeYRot(player.yRot, old.yRot);
            player.xRot = RotationUpdate.storeXRot(player.xRot, old.xRot);
        } else {
            resetPos(player, data);
            player.yRot = RotationUpdate.storeYRot(player.yRot, -180.0F);
            resolves.add(Unmodeled.ENTITY_POSE);
        }
        ClientPlayerHandlers.health(player, data.health);
        if ((respawn.dataToKeep() & ClientboundRespawnPacket.KEEP_ATTRIBUTE_MODIFIERS) != 0) {
            player.attributes.assignAllValues(old.attributes);
        } else {
            player.attributes.assignBaseValues(old.attributes);
        }
        if (data.localMode == null) {
            throw new IllegalStateException("Respawn needs the resolved local game mode of the hypothesis");
        }
        player.abilities.updateGameMode(data.localMode);
        setLocalMode(player, data, ru.minecomplex.mac.packet.Packets.gameModeId(respawn.commonPlayerSpawnInfo().gameType()));
        data.food = 20;
        data.saturation = 5.0F;
        data.clientLoaded = false;
        data.inventory = new CompensatedInventory();
        // a new player: attackStrengthTicker 0, lastItemInMainHand empty
        ClientAttack.newPlayer(data);
        // the game mode (and its carriedIndex) stays: a selected slot other than 0 is sent at the next tick
        data.tick = new TickActions();
        newPlayerUse(data);
        data.resetPlayerInfoCache();
        player.spectator = data.peekPlayerInfoMode() == GameType.SPECTATOR;
        // the new player computed its dimensions at the default scale (entity data assigns the pose before the
        // attributes): dimensionScale stays 1 until the next refresh takes a kept scale
        Set<Unmodeled> gaps = EnumSet.of(Unmodeled.RESPAWN_POSITION);
        return new Recreated(player, local, gaps, resolves);
    }

    /**
     * {@code handleLogin} in a new play listener (no {@code PlayerInfo} yet) and a new {@code MultiPlayerGameMode}
     * (survival, {@code carriedIndex} 0): {@code Minecraft.player} is null after the configuration phase, so {@code createPlayer},
     * {@code setYRot(-180)}, {@code resetPos()}, a new {@code KeyboardInput}, {@code adjustPlayer},
     * {@code setLocalMode(spawn game type)}.
     *
     * @param old      previous player of this connection, or {@code null}; only client-global state is kept
     * @param oldLocal previous local state, or {@code null}
     */
    public static Recreated login(PlayerState old, LocalPlayerState oldLocal, ClientPlayerData data, ClientboundLoginPacket join) {
        PlayerState player = newPlayer(old);
        LocalPlayerState local = newLocal(oldLocal);
        player.yRot = RotationUpdate.storeYRot(player.yRot, -180.0F);
        ClientPlayerData fresh = new ClientPlayerData();
        data.entityId = join.playerId();
        data.health = fresh.health;
        data.food = fresh.food;
        data.saturation = fresh.saturation;
        data.resetPlayerInfoCache();
        data.infoPresent = false;
        data.infoMode = GameType.SURVIVAL;
        data.clientLoaded = false;
        data.inventory = new CompensatedInventory();
        // a new player: attackStrengthTicker 0, lastItemInMainHand empty
        ClientAttack.newPlayer(data);
        data.carriedIndex = 0;
        data.tick = new TickActions();
        newPlayerUse(data);
        resetPos(player, data);
        ClientPlayerHandlers.health(player, data.health);
        player.abilities.updateGameMode(GameType.SURVIVAL);
        setLocalMode(player, data, ru.minecomplex.mac.packet.Packets.gameModeId(join.commonPlayerSpawnInfo().gameType()));
        player.spectator = false;
        Set<Unmodeled> resolves = EnumSet.of(Unmodeled.PLAYER_INFO_IDENTITY, Unmodeled.ENTITY_POSE, Unmodeled.INVENTORY_CONTAINER, Unmodeled.DROP_SLOT, Unmodeled.INVENTORY_CLIENT_ACTION, Unmodeled.ITEM_USE_TIMING, Unmodeled.ITEM_USE_OUTCOME, Unmodeled.RIPTIDE);
        return new Recreated(player, local, Set.of(Unmodeled.RESPAWN_POSITION), resolves);
    }

    /**
     * A new LocalPlayer uses no item and has new {@code ItemCooldowns}.
     */
    private static void newPlayerUse(ClientPlayerData data) {
        ClientItemUse.stop(data);
        data.cooldowns.clear();
        data.cooldownClock = 0;
        data.useOutcomeInventory = false;
        data.useMayContinue = false;
        data.useUncertain = false;
        data.useOutcomeFood = false;
        data.uncertainCooldowns.clear();
    }

    /**
     * A new entity's state, keeping the client-global fluid shape cache.
     */
    private static PlayerState newPlayer(PlayerState old) {
        PlayerState player = new PlayerState();
        if (old != null) {
            player.fluidShapes = old.fluidShapes;
        }
        return player;
    }

    /**
     * A new LocalPlayer with a new {@code KeyboardInput}; key mapping state belongs to {@code Options}.
     */
    private static LocalPlayerState newLocal(LocalPlayerState old) {
        LocalPlayerState local = new LocalPlayerState();
        if (old != null) {
            local.controls = old.controls;
        }
        return local;
    }

    /**
     * {@code LocalPlayer.resetPos()}: standing pose, the upward collision search (position unresolved), zero delta
     * movement, pitch 0 and {@code setHealth(getMaxHealth())} with the new player's default maximum.
     */
    private static void resetPos(PlayerState player, ClientPlayerData data) {
        player.pose = Pose.STANDING;
        player.deltaMovement = Vec3d.ZERO;
        player.xRot = RotationUpdate.storeXRot(player.xRot, 0.0F);
        data.health = NEW_PLAYER_MAX_HEALTH;
    }

    /**
     * {@code MultiPlayerGameMode.setLocalMode(mode, previous)}: the new mode updates the abilities.
     */
    private static void setLocalMode(PlayerState player, ClientPlayerData data, int gameMode) {
        data.localMode = GameType.byId(gameMode);
        player.abilities.updateGameMode(data.localMode);
    }
}
