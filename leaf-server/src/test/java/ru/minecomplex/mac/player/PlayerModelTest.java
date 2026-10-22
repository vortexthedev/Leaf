package ru.minecomplex.mac.player;

import org.junit.jupiter.api.Test;

import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;

import net.minecraft.world.level.GameType;

import ru.minecomplex.mac.inventory.item.Stacks;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import ru.minecomplex.mac.frame.BetweenTicks;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.frame.FrameListener;
import ru.minecomplex.mac.fixture.WireItem;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.util.math.Vec3d;

import ru.minecomplex.mac.fixture.inbound.TeleportAccept;
import ru.minecomplex.mac.fixture.outbound.CloseContainer;
import ru.minecomplex.mac.fixture.outbound.ContainerSlot;
import ru.minecomplex.mac.fixture.outbound.CursorItem;
import ru.minecomplex.mac.fixture.outbound.EffectRemove;
import ru.minecomplex.mac.fixture.outbound.EffectUpdate;
import ru.minecomplex.mac.fixture.outbound.EntityAttributes;
import ru.minecomplex.mac.fixture.outbound.EntityVelocity;
import ru.minecomplex.mac.fixture.outbound.Explosion;
import ru.minecomplex.mac.fixture.outbound.GameEvent;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.MountScreenOpen;
import ru.minecomplex.mac.fixture.outbound.OpenContainer;
import ru.minecomplex.mac.fixture.outbound.PlayerInfoGameMode;
import ru.minecomplex.mac.fixture.outbound.PlayerInfoRemove;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.player.transaction.MarkerPolicy;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.listener.ClientPlayerActions;
import ru.minecomplex.mac.listener.ClientPlayerHandlers;
import ru.minecomplex.mac.player.state.ClientPlayerData;

/** Selection, prefix application, forks and gaps of the client-player hypotheses (the arithmetic is MAPS-tested). */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class PlayerModelTest {

    private static PlayerModel.Hypothesis start(PlayerModel model, long appliedThrough) {
        return model.start(new PlayerState(), new LocalPlayerState(), new ClientPlayerData(), appliedThrough);
    }

    @Test
    void selectsChangesOfTheOwnPlayerOnly() {
        assertTrue(PlayerModel.isPlayerChange(Wire.of(new EntityVelocity(7, 0, 0, 0)), 7, Wire.FACTS.localUuid()));
        assertFalse(PlayerModel.isPlayerChange(Wire.of(new EntityVelocity(8, 0, 0, 0)), 7, Wire.FACTS.localUuid()));
        assertTrue(PlayerModel.isPlayerChange(Wire.of(new EffectUpdate(7, "minecraft:speed", 0, 20, false, true, true, false)), 7, Wire.FACTS.localUuid()));
        assertFalse(PlayerModel.isPlayerChange(Wire.of(new EffectRemove(8, "minecraft:speed")), 7, Wire.FACTS.localUuid()));
        assertFalse(PlayerModel.isPlayerChange(Wire.of(EntityAttributes.of(8, List.of())), 7, Wire.FACTS.localUuid()));
        assertTrue(PlayerModel.isPlayerChange(Wire.of(new Explosion(0, 0, 0, true, 1, 0, 0)), 7, Wire.FACTS.localUuid()));
        assertFalse(PlayerModel.isPlayerChange(Wire.of(new Explosion(0, 0, 0, false, 0, 0, 0)), 7, Wire.FACTS.localUuid()));
        assertTrue(PlayerModel.isPlayerChange(Wire.of(new GameEvent(GameEvent.CHANGE_GAME_MODE, 1)), 7, Wire.FACTS.localUuid()));
        assertFalse(PlayerModel.isPlayerChange(Wire.of(new GameEvent(GameEvent.IMMEDIATE_RESPAWN, 1)), 7, Wire.FACTS.localUuid()));
        assertTrue(MarkerPolicy.markerAfter(Wire.of(EntityAttributes.of(7, List.of())), 7, Wire.FACTS.localUuid()));
        assertFalse(MarkerPolicy.markerAfter(Wire.of(EntityAttributes.of(8, List.of())), 7, Wire.FACTS.localUuid()));
    }

    @Test
    void appliesPrefixesInWireOrderAndForksIndependently() {
        PlayerModel model = new PlayerModel();
        PlayerModel.Hypothesis hypothesis = start(model, -1);
        model.sent(3, Wire.of(new EntityVelocity(1, 0.5, 0, 0)));
        model.sent(5, Wire.of(new PlayerPosition(9, 10, 64, 10, 0, 0, 0, 0, 0, PlayerPosition.RELATIVE_DELTA_X)));
        SyncWindow window = new SyncWindow(-1, 6);

        assertEquals(List.of(), hypothesis.apply(window, 3));
        assertEquals(0.5, hypothesis.player().deltaMovement.x());
        PlayerModel.Hypothesis fork = hypothesis.fork();

        List<PlayerModel.Reply> replies = hypothesis.apply(window, 5);
        assertEquals(new Vec3d(10, 64, 10), hypothesis.player().position);
        assertEquals(0.5, hypothesis.player().deltaMovement.x(), "relative delta keeps the current value");
        assertEquals(1, replies.size());
        assertEquals(5, replies.getFirst().cause());
        assertEquals(9, assertInstanceOf(TeleportAccept.class, Wire.in(replies.get(0).packet())).id());
        assertEquals(TeleportAccept.withPosition(9,10,64,10,0,0),Wire.in(replies.getFirst().packet()));

        assertEquals(Vec3d.ZERO, fork.player().position, "a fork does not see the other branch's packets");
        fork.player().deltaMovement = new Vec3d(2, 0, 0);
        fork.apply(window, 5);
        assertEquals(2.0, fork.player().deltaMovement.x());
        assertEquals(0.5, hypothesis.player().deltaMovement.x());

        assertThrows(IllegalArgumentException.class, () -> hypothesis.apply(window, 3), "prefixes never go backwards");
        assertThrows(IllegalArgumentException.class, () -> fork.apply(new SyncWindow(-1, 5), 5), "outside the window");
    }

    @Test
    void newHypothesesCannotStartBeforeForgottenChanges() {
        PlayerModel model = new PlayerModel();
        model.sent(1, Wire.of(new GameEvent(GameEvent.CHANGE_GAME_MODE, 1)));
        model.settle(new SyncWindow(1, 2));
        assertEquals(0, model.pendingCount());
        assertThrows(IllegalArgumentException.class, () -> start(model, 0));
        model.sent(4, Wire.of(new GameEvent(GameEvent.CHANGE_GAME_MODE, 3)));
        PlayerModel.Hypothesis hypothesis = start(model, 1);
        hypothesis.apply(new SyncWindow(1, 5), 4);
        assertEquals(GameType.SPECTATOR, hypothesis.data().localMode);
        assertTrue(hypothesis.player().abilities.flying);
    }

    @Test
    void removingAnAbsentPlayerInfoDoesNotMakeRecreationUncertain() {
        // Login starts with no map entry and no cache; removing a missing entry has no effect.
        PlayerModel model = new PlayerModel();
        PlayerModel.Hypothesis hypothesis = start(model, -1);
        model.sent(1, Wire.of(new JoinGame(7, "minecraft:overworld", -64, 384, 10)));
        model.sent(2, Wire.of(new PlayerInfoRemove()));
        hypothesis.apply(new SyncWindow(-1, 3), 2);
        assertEquals(Set.of(Unmodeled.RESPAWN_POSITION), hypothesis.gaps());
        model.sent(3, Wire.of(new PlayerPosition(1, 0.5, 64, 0.5, 0, 0, 0, 0, 0, PlayerPosition.RELATIVE_Y_ROT)));
        hypothesis.apply(new SyncWindow(-1, 4), 3);
        assertEquals(Set.of(), hypothesis.gaps(), "an absolute teleport fixes the position");
    }

    @Test
    void scaleChangesWaitForTheDimensionRefresh() {
        ClientPlayerHandlers handlers = new ClientPlayerHandlers();
        PlayerState player = new PlayerState();
        ClientPlayerData data = new ClientPlayerData();
        var speed = EntityAttributes.of(1, List.of(new ru.minecomplex.mac.entity.attribute.AttributeSnapshot("minecraft:movement_speed", 0.2, List.of())));
        assertEquals(Set.of(), handlers.apply(player, new LocalPlayerState(), data, Wire.of(speed)).gaps());
        var scale = EntityAttributes.of(1, List.of(new ru.minecomplex.mac.entity.attribute.AttributeSnapshot("minecraft:scale", 2.0, List.of())));
        assertEquals(Set.of(), handlers.apply(player, new LocalPlayerState(), data, Wire.of(scale)).gaps());
        assertEquals(1.8F, player.height(), "the cached dimensions keep the old scale");
        player.refreshDimensions();
        assertEquals(3.6F, player.height());
    }

    @Test
    void openContainersApplyThroughTheirLayoutAndMountScreensAreUncertain() {
        ClientPlayerHandlers handlers = new ClientPlayerHandlers();
        PlayerState player = new PlayerState();
        ClientPlayerData data = new ClientPlayerData();
        WireItem boots = WireItem.of("minecraft:leather_boots", 1);
        handlers.apply(player, new LocalPlayerState(), data, Wire.of(new OpenContainer(3, 2)));
        assertEquals(Set.of(), handlers.apply(player, new LocalPlayerState(), data, Wire.of(ContainerSlot.of(3, 7, 54, boots))).gaps());
        assertTrue(Stacks.is(data.inventory.item(0), "minecraft:leather_boots"), "generic_9x3 slot 54 is hotbar slot 0");
        assertEquals(7, data.inventory.openStateId());
        assertEquals(Set.of(), handlers.apply(player, new LocalPlayerState(), data, Wire.of(ContainerSlot.of(4, 8, 54, WireItem.EMPTY))).gaps());
        assertTrue(Stacks.is(data.inventory.item(0), "minecraft:leather_boots"), "another container id is ignored");

        handlers.apply(player, new LocalPlayerState(), data, Wire.of(new OpenContainer(5, 18)));
        assertEquals(Set.of(), handlers.apply(player, new LocalPlayerState(), data, Wire.of(ContainerSlot.of(5, 1, 31, boots))).gaps(), "the loom");
        assertTrue(data.inventory.openMenuUnknown(3), "the loom computes its result on the client");
        assertTrue(Stacks.is(data.inventory.item(0), "minecraft:leather_boots"), "loom slot 31 is hotbar slot 0");

        // a mount screen for a known horse opens its menu: saddle, armor, 3 x 2 chest slots, then the inventory
        ClientPlayerActions.Target horses = new ClientPlayerActions.Target() {
            @Override
            public String block(int x, int y, int z) {
                return null;
            }

            @Override
            public String entityType(int entityId) {
                return entityId == 42 ? "minecraft:donkey" : "minecraft:pig";
            }
        };
        assertEquals(Set.of(), handlers.apply(player, new LocalPlayerState(), data, Wire.of(new MountScreenOpen(8, 2, 42)), horses).gaps());
        assertEquals(8, data.inventory.openContainerId());
        assertEquals(44, data.inventory.openMenuSize());
        assertEquals(Set.of(), handlers.apply(player, new LocalPlayerState(), data, Wire.of(new MountScreenOpen(9, 2, 43)), horses).gaps());
        assertEquals(8, data.inventory.openContainerId(), "a pig opens nothing");

        handlers.apply(player, new LocalPlayerState(), data, Wire.of(new OpenContainer(3, 2)));
        assertEquals(Set.of(Unmodeled.INVENTORY_CONTAINER), handlers.apply(player, new LocalPlayerState(), data, Wire.of(new MountScreenOpen(6, 3, 42))).gaps());
        for (int id : new int[]{3, 6}) {
            assertEquals(Set.of(Unmodeled.INVENTORY_CONTAINER),
                    handlers.apply(player, new LocalPlayerState(), data, Wire.of(ContainerSlot.of(id, 1, 54, WireItem.EMPTY))).gaps(), "either menu may be open: " + id);
        }
        assertEquals(Set.of(), handlers.apply(player, new LocalPlayerState(), data, Wire.of(ContainerSlot.of(7, 1, 54, WireItem.EMPTY))).gaps());
        assertEquals(Set.of(Unmodeled.INVENTORY_CONTAINER), handlers.apply(player, new LocalPlayerState(), data, Wire.of(CursorItem.of(boots))).gaps());
        handlers.apply(player, new LocalPlayerState(), data, Wire.of(new CloseContainer(6)));
        assertEquals(Set.of(), handlers.apply(player, new LocalPlayerState(), data, Wire.of(CursorItem.of(boots))).gaps());
        assertEquals(Set.of(), handlers.apply(player, new LocalPlayerState(), data, Wire.of(ContainerSlot.of(6, 1, 54, WireItem.EMPTY))).gaps());
        assertThrows(IllegalArgumentException.class, () -> handlers.apply(player, new LocalPlayerState(), data, Wire.of(new OpenContainer(8, 25))));
    }

    @Test
    void playerInfoUpdatesNeedAnEntryAndCallBackOnlyOnChange() {
        ClientPlayerHandlers handlers = new ClientPlayerHandlers();
        PlayerState player = new PlayerState();
        ClientPlayerData data = new ClientPlayerData();
        player.deltaMovement = new Vec3d(0.1, 0.2, 0.3);
        handlers.apply(player, new LocalPlayerState(), data, Wire.of(new PlayerInfoGameMode(false, true, 3)));
        assertFalse(player.spectator, "no PlayerInfo yet: the update is ignored");
        handlers.apply(player, new LocalPlayerState(), data, Wire.of(new PlayerInfoGameMode(true, true, 3)));
        assertTrue(player.spectator);
        assertEquals(new Vec3d(0.1, 0.0, 0.3), player.deltaMovement, "onGameModeChanged(spectator)");
        player.deltaMovement = new Vec3d(0.1, 0.2, 0.3);
        handlers.apply(player, new LocalPlayerState(), data, Wire.of(new PlayerInfoGameMode(true, true, 3)));
        assertEquals(0.2, player.deltaMovement.y(), "unchanged mode: no callback, and ADD keeps the existing entry");
        handlers.apply(player, new LocalPlayerState(), data, Wire.of(new PlayerInfoGameMode(false, true, 9)));
        assertFalse(player.spectator, "unknown ids are survival");
    }

    @Test
    void sessionFeedsOwnPlayerChanges() {
        MacPlayer session = new MacPlayer(new FrameListener() {
            @Override
            public void betweenTicks(BetweenTicks event) {
            }

            @Override
            public void frame(ClientTickFrame frame) {
            }
        });
        var join = (net.minecraft.network.protocol.game.ClientboundLoginPacket)
                Wire.of(new JoinGame(5, "minecraft:overworld", -64, 384, 10));
        ru.minecomplex.mac.fixture.FixturePackets.send(session, join, 0);
        Wire.send(session, new EntityVelocity(5, 0, 1, 0), 0);
        Wire.send(session, new EntityVelocity(6, 0, 1, 0), 0);
        assertEquals(2, session.player().pendingCount());
    }
}
