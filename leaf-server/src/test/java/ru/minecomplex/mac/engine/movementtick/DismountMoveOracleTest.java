package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;
import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.entity.PassengerGraph;
import ru.minecomplex.mac.player.state.ClientPlayerData;
import ru.minecomplex.mac.listener.ClientPlayerHandlers;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.entity.BoatState;
import ru.minecomplex.mac.entity.interpolation.BoatInterpolationOracleTest;
import ru.minecomplex.mac.mechanics.fluid.FluidShapeCache;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * MADM v1: real boat/raft ride ticks, the server's dismount ({@code set_passengers} without the player and the
 * {@code dismountTo} teleport, either order) and real on-foot ticks with movement keys from the first tick on, replayed
 * through the components the movement probe chains: {@link BoatPlayerTick}, the passenger synchronization, the teleport
 * handler and a new {@link MovementTickerPlayer} per tick. Compared bit for bit after every step.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class DismountMoveOracleTest {

    @Test
    void rideDismountAndWalk() throws Exception {
        var registry = BlockRegistry.get();
        String path = "/vanilla/777/dismount-moves.bin.gz";
        var stream = getClass().getResourceAsStream(path);
        assertNotNull(stream, path);
        try (var in = new DataInputStream(new GZIPInputStream(stream))) {
            assertEquals(0x4d41444d, in.readInt());
            assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            int scenarios = in.readInt();
            for (int s = 0; s < scenarios; s++) {
                boolean raft = in.readBoolean();
                var blocks = new ArrayList<LocalTickOracle.Block>();
                for (int n = in.readInt(); n > 0; n--) blocks.add(new LocalTickOracle.Block(in.readInt(), in.readInt(), in.readInt(), in.readInt()));
                float width = in.readFloat(), height = in.readFloat(), eye = in.readFloat();
                var initial = BoatTickOracleTest.Recorded.read(in);
                var own = BoatPlayerOracleTest.Own.read(in);
                var graph = new PassengerGraph();
                graph.spawn(1, true);
                graph.spawn(2);
                graph.startRiding(1, 2, true);
                var boat = new BoatState(graph.get(2).generation(), width, height, eye);
                boat.body = initial.body();
                boat.body.fixedBodyDimensions = true;
                boat.body.bodyWidth = width;
                boat.body.bodyHeight = height;
                boat.body.firstTick = initial.firstTick();
                boat.body.yRot = initial.motion().yaw();
                boat.body.fluidShapes = FluidShapeCache.empty();
                initial.motion().applyTo(boat, false);
                boat.tickCount = initial.tickCount();
                boat.outOfControlTicks = initial.outOfControl();
                var player = own.player(false);
                player.fluidShapes = FluidShapeCache.empty();
                var local = own.hidden();
                var env = LocalPlayerFluidOracleTest.environment(registry, blocks);
                var attachment = new Vec3d[] { Vec3d.ZERO };
                var playerData = new ClientPlayerData();
                playerData.food = own.local().food();
                var simulation = new BoatPlayerTick(env, graph, 1, 2, boat, player, local, playerData, raft, false, p -> attachment[0], box -> List.of());
                for (int count = in.readInt(), t = 0; t < count; t++) {
                    boolean loaded = in.readBoolean();
                    var input = PlayerInput.fromMask(in.readUnsignedByte());
                    player.yRot = in.readFloat();
                    player.xRot = in.readFloat();
                    attachment[0] = BoatInterpolationOracleTest.vector(in);
                    boolean authority = in.readBoolean(), controlled = in.readBoolean();
                    playerData.clientLoaded = loaded;
                    var result = simulation.simulateEntityTicks(input, authority ? Tri.TRUE : Tri.FALSE, controlled, true);
                    assertFalse(in.readBoolean());
                    {
                        result = result.then(simulation.sendAfterEntityTicks());
                    }
                    String context = "26.3 scenario " + s + " ride tick " + t;
                    BoatTickOracleTest.Recorded.read(in).compare(boat, context + " boat");
                    BoatPlayerOracleTest.Own.read(in).compare(player, local, context + " player");
                    for (int i = 0; i < 4; i++) in.readBoolean();
                    var packets = new ArrayList<InboundEvent>();
                    for (int n = in.readInt(); n > 0; n--) packets.add(LocalPlayerOracleCodec.packet(in));
                    assertEquals(packets, Wire.in(result.packets()), context + " packets");
                }
                boolean teleportFirst = in.readBoolean();
                var teleport = new PlayerPosition(0, in.readDouble(), in.readDouble(), in.readDouble(), 0.0, 0.0, 0.0, 0.0F, 0.0F, in.readInt());
                var handlers = new ClientPlayerHandlers();
                var data = new ClientPlayerData();
                if (teleportFirst)
                    handlers.apply(player, local, data, Wire.of(teleport));
                // set_passengers without the player: ejectPassengers -> stopRiding (the probe's passenger synchronization)
                graph.stopRiding(1);
                player.passenger = false;
                local.handsBusy = false;
                local.rootVehicle = null;
                if (!teleportFirst)
                    handlers.apply(player, local, data, Wire.of(teleport));
                compareOnFoot(BoatPlayerOracleTest.Own.read(in), player, local, "26.3 scenario " + s + " dismounted");
                for (int count = in.readInt(), t = 0; t < count; t++) {
                    var input = PlayerInput.fromMask(in.readUnsignedByte());
                    player.yRot = in.readFloat();
                    player.xRot = in.readFloat();
                    playerData.clientLoaded = true;
                    var ticker = new MovementTickerPlayer(env, player, local, playerData);
                    var gaps = ticker.tick(input);
                    String context = "26.3 scenario " + s + " foot tick " + t + " first " + input;
                    assertEquals(Set.of(), gaps, context);
                    compareOnFoot(BoatPlayerOracleTest.Own.read(in), player, local, context);
                    var packets = new ArrayList<InboundEvent>();
                    for (int n = in.readInt(); n > 0; n--) packets.add(LocalPlayerOracleCodec.packet(in));
                    assertEquals(packets, Wire.in(ticker.packets()), context + " packets");
                }
            }
            assertEquals(-1, in.read());
        }
    }

    /**
     * {@link BoatPlayerOracleTest.Own#compare} without the old, head and body yaws: a player on foot keeps them only for
     * rendering (the seat reads them while riding).
     */
    static void compareOnFoot(BoatPlayerOracleTest.Own own, PlayerState p, LocalPlayerState l, String context) {
        var copy = new LocalPlayerState();
        copy.input = l.input;
        copy.vector = l.vector;
        copy.crouching = l.crouching;
        copy.wasFallFlying = l.wasFallFlying;
        copy.jumpTriggerTime = l.jumpTriggerTime;
        copy.sprintTriggerTime = l.sprintTriggerTime;
        copy.autoJumpTime = l.autoJumpTime;
        copy.jumpRidingScale = l.jumpRidingScale;
        copy.jumpRidingTicks = l.jumpRidingTicks;
        copy.waterVisionTime = l.waterVisionTime;
        copy.send = l.send;
        copy.handsBusy = l.handsBusy;
        copy.yawOld = own.yawOld();
        copy.headYaw = own.head();
        copy.bodyYaw = own.body();
        own.compare(p, copy, context);
    }
}
