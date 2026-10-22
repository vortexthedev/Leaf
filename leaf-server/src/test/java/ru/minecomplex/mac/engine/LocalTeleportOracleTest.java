package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.replica.CompensatedEntities;
import ru.minecomplex.mac.entity.Pose;
import ru.minecomplex.mac.listener.ClientPlayerActions;
import ru.minecomplex.mac.player.state.ClientPlayerData;
import ru.minecomplex.mac.listener.ClientPlayerHandlers;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.fixture.inbound.TeleportAccept;
import ru.minecomplex.mac.fixture.outbound.EntityPassengers;
import ru.minecomplex.mac.fixture.outbound.EntityRemove;
import ru.minecomplex.mac.fixture.outbound.EntitySpawn;
import ru.minecomplex.mac.fixture.outbound.EntityTeleport;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.BlockRegistry;
import static org.junit.jupiter.api.Assertions.*;
import ru.minecomplex.mac.engine.movementtick.LocalPlayerOracleCodec;
import ru.minecomplex.mac.engine.movementtick.MovementTickerPlayer;
import ru.minecomplex.mac.engine.movementtick.LocalPlayerPhysicsOracleTest;
import ru.minecomplex.mac.engine.movementtick.LocalTickOracle;
import ru.minecomplex.mac.engine.movementtick.MovementOracleTest;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;

/**
 * MALT v1: {@code teleport_entity} reaching the own player through {@link ClientPlayerHandlers} (own id, former vehicle
 * tracked by {@link CompensatedEntities}), {@code player_position} relative to the interpolation target, then real chained
 * LocalPlayer ticks with the own interpolation step ({@link MovementTickerPlayer}); compared bit for bit with the real
 * client, handler replies included.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class LocalTeleportOracleTest {
    static final int PLAYER_ID = 1, BOAT_ID = 77;

    @Test
    void chainedTicks() throws Exception {
        var raw = getClass().getResourceAsStream("/vanilla/777/local-teleport-ticks.bin.gz");
        assertNotNull(raw, "26.3");
        var registry = BlockRegistry.get();
        int interpolatedTicks = 0, redirected = 0;
        try (var in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(raw)))) {
            assertEquals(0x4d414c54, in.readInt());
            assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            org.junit.jupiter.api.Assertions.assertEquals("26.3", in.readUTF(), "Fixture version");
            int scenarios = in.readInt();
            for (int scenario = 0; scenario < scenarios; scenario++) {
                String family = in.readUTF();
                int blockCount = in.readInt();
                var blocks = new ArrayList<LocalTickOracle.Block>();
                for (int b = 0; b < blockCount; b++) blocks.add(new LocalTickOracle.Block(in.readInt(), in.readInt(), in.readInt(), in.readInt()));
                var initial = LocalTickOracle.State.read(in);
                var base = initial.base();
                PlayerState player = base.player().toState();
                var local = new LocalPlayerState();
                local.input = base.input(); local.vector = base.vector(); local.send = base.sendState();
                local.send.wasShiftKeyDown = initial.wasShift();
                var data = new ClientPlayerData();
                data.entityId = PLAYER_ID; data.clientLoaded=true; data.food=initial.food();
                var handlers = new ClientPlayerHandlers();
                var entities = new CompensatedEntities(PLAYER_ID);
                ClientPlayerActions.Target target = new ClientPlayerActions.Target() {
                    @Override public String block(int x, int y, int z) { return null; }
                    @Override public String entityType(int entityId) {
                        var spawn = entities.spawnInput(entityId);
                        return spawn == null ? null : ru.minecomplex.mac.packet.Packets.type(spawn);
                    }
                    @Override public Tri formerVehicle(int entityId) { return entities.formerVehicle(entityId); }
                };
                var physics = new MovementTickerPlayer(LocalPlayerPhysicsOracleTest.environment(registry, blocks), player, local,data);
                int ticks = in.readInt();
                for (int tick = 0; tick < ticks; tick++) {
                    String context = "26.3" + " " + family + " scenario " + scenario + " tick " + tick;
                    int keys = in.readUnsignedByte();
                    player.yRot = in.readFloat();
                    player.xRot = in.readFloat();
                    var replies = new ArrayList<InboundEvent>();
                    int ops = in.readInt();
                    for (int op = 0; op < ops; op++) {
                        int kind = in.readUnsignedByte();
                        switch (kind) {
                            case 0 -> {
                                var teleport = new EntityTeleport(in.readInt(), in.readDouble(), in.readDouble(), in.readDouble(),
                                        in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat(), in.readInt(),
                                        in.readBoolean());
                                if (teleport.entityId() != PLAYER_ID && entities.formerVehicle(teleport.entityId()) == Tri.TRUE) redirected++;
                                entities.motion(Wire.of(teleport), chunk -> true);
                                var result = handlers.apply(player, local, data, Wire.of(teleport), target);
                                assertEquals(java.util.Set.of(), result.gaps(), context);
                                replies.addAll(Wire.in(result.replies()));
                            }
                            case 1 -> {
                                var teleport = new PlayerPosition(in.readInt(), in.readDouble(), in.readDouble(), in.readDouble(),
                                        in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat(), in.readInt());
                                replies.addAll(Wire.in(handlers.apply(player, local, data, Wire.of(teleport), target).replies()));
                            }
                            // move_entity and entity_position_sync of the own id: the client only updates its codec
                            case 2 -> { in.readShort(); in.readShort(); in.readShort(); in.readByte(); in.readByte(); in.readBoolean(); }
                            case 3 -> { in.readDouble(); in.readDouble(); in.readDouble(); in.readFloat(); in.readFloat(); in.readBoolean(); }
                            case 4 -> entities.spawn(Wire.of(new EntitySpawn(BOAT_ID, "minecraft:oak_boat", in.readDouble(), in.readDouble(),
                                    in.readDouble(), 0.0F, 0.0F, 0.0F, 0, false, 0.0, 0.0, 0.0)), chunk -> true);
                            case 5 -> entities.apply(Wire.of(new EntityPassengers(BOAT_ID, new int[]{PLAYER_ID})));
                            case 6 -> entities.remove(Wire.of(new EntityRemove(new int[]{BOAT_ID})));
                            default -> throw new IOException("Unknown MALT op " + kind);
                        }
                        syncPassenger(entities, player, local);
                    }
                    var expectedReplies = new ArrayList<InboundEvent>();
                    int replyCount = in.readInt();
                    for (int r = 0; r < replyCount; r++) expectedReplies.add(reply(in));
                    assertEquals(expectedReplies, replies, context + " replies");
                    var expected = LocalTickOracle.State.read(in);
                    var packets = new ArrayList<InboundEvent>();
                    int count = in.readInt();
                    for (int p = 0; p < count; p++) packets.add(LocalPlayerOracleCodec.packet(in));
                    boolean interpolating = in.readBoolean();
                    var result = physics.tick(PlayerInput.fromMask(keys));
                    assertTrue(result.isEmpty(), context + result);
                    assertEquals(List.of(), expected.base().player().differences(MovementOracleTest.Recorded.of(player)), context);
                    assertEquals(packets, Wire.in(physics.packets()), context);
                    assertEquals(interpolating, player.interpolation != null && player.interpolation.active(), context + " interpolating");
                    if (interpolating) interpolatedTicks++;
                }
            }
            assertEquals(-1, in.read());
        }
        assertTrue(interpolatedTicks > 100, "26.3: " + interpolatedTicks + " interpolating ticks");
        assertTrue(redirected > 4, "26.3: " + redirected + " former-vehicle teleports");
    }

    /** {@code startRiding}: the standing pose; {@code removeVehicle}: free hands (the probe's passenger synchronization). */
    private static void syncPassenger(CompensatedEntities entities, PlayerState player, LocalPlayerState local) {
        var own = entities.passengers().get(PLAYER_ID);
        boolean riding = own != null && own.vehicle() != null;
        if (riding == player.passenger) return;
        if (riding && player.pose != Pose.STANDING) {
            player.pose = Pose.STANDING;
            player.refreshDimensions();
        }
        if (!riding) local.handsBusy = false;
        player.passenger = riding;
    }

    private static InboundEvent reply(DataInputStream in) throws IOException {
        in.mark(1);
        int kind = in.readUnsignedByte();
        if (kind != 8) {
            in.reset();
            return LocalPlayerOracleCodec.packet(in);
        }
        int id = in.readInt();
        boolean withPosition = in.readBoolean();
        if (!withPosition) return new TeleportAccept(id, false, 0.0, 0.0, 0.0, 0.0F, 0.0F);
        return new TeleportAccept(id, true, in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat());
    }
}
