package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.player.state.ClientPlayerData;
import ru.minecomplex.mac.Wire;
import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.util.ArrayList;

import java.util.Set;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.entity.PassengerGraph;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.entity.HorseState;
import ru.minecomplex.mac.mechanics.fluid.FluidContactOracleTest;
import ru.minecomplex.mac.mechanics.fluid.FluidShapeCache;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * Real concrete horse.tick and mounted LocalPlayer, including retained input, fluids and body rotation.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
public class HorseWholePlayerOracleTest {

    static HorseOracle.Control facts(DataInputStream in, HorseState.Kind kind) throws Exception {
        return new HorseOracle.Control(kind, true, true, false, in.readBoolean(), in.readBoolean(), in.readLong(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat(), in.readFloat());
    }

    static void tail(DataInputStream in, HorseState state, String context) throws Exception {
        HorseControlOracleTest.compare(in, state, state.kind == HorseState.Kind.CAMEL, context, false);
        HorseWholePlayerOracleTest.bits(in.readFloat(), state.standAnimation, context);
        HorseWholePlayerOracleTest.bits(in.readFloat(), state.oldStandAnimation, context);
        assertEquals(in.readUTF(), state.pose.name(), context);
    }

    public static void bits(float e, float a, String s) {
        assertEquals(Float.floatToIntBits(e), Float.floatToIntBits(a), s);
    }

    static HorseOracle.Control effective(HorseOracle.Control f, boolean ai) {
        return new HorseOracle.Control(f.kind(), f.saddled(), f.localAuthority(), ai, f.onGround(), f.canRear(), f.gameTime(), f.speed(), f.jumpStrength(), f.blockJumpFactor(), f.blockSpeedFactor(), f.jumpBoost());
    }

    static void extra(DataInputStream in, HorseState state, boolean init, String context) throws Exception {
        int stable = in.readInt();
        float head = in.readFloat();
        int delay = in.readInt();
        double water = in.readDouble(), lava = in.readDouble();
        boolean wet = in.readBoolean(), hot = in.readBoolean();
        boolean eyeWater = in.readBoolean(), eyeLava = in.readBoolean(), oldEye = in.readBoolean(), first = in.readBoolean();
        float xxa = in.readFloat(), yya = in.readFloat(), zza = in.readFloat();
        boolean sprint = in.readBoolean(), swim = in.readBoolean(), jump = in.readBoolean();
        var p = state.body;
        if (init) {
            state.bodyRotation.headStableTime = stable;
            state.bodyRotation.lastStableHeadYaw = head;
            p.noJumpDelay = delay;
            p.waterHeight = water;
            p.lavaHeight = lava;
            p.wasTouchingWater = wet;
            p.isInLava = hot;
            p.eyeInWater = eyeWater;
            p.eyeInLava = eyeLava;
            p.wasEyeInWater = oldEye;
            p.firstTick = first;
            p.xxa = xxa;
            p.yya = yya;
            p.zza = zza;
            p.sprinting = sprint;
            p.swimming = swim;
            p.jumping = jump;
            return;
        }
        assertEquals(stable, state.bodyRotation.headStableTime, context + " stable time");
        bits(head, state.bodyRotation.lastStableHeadYaw, context + " stable yaw");
        assertEquals(sprint, p.sprinting, context + " sprint");
        assertEquals(swim, p.swimming, context + " swim");
        assertEquals(jump, p.jumping, context + " living jumping");
        assertEquals(delay, p.noJumpDelay, context + " jump delay");
        FluidContactOracleTest.bits(water, p.waterHeight, context + " water");
        FluidContactOracleTest.bits(lava, p.lavaHeight, context + " lava");
        assertEquals(wet, p.wasTouchingWater, context + " wet");
        assertEquals(hot, p.isInLava, context + " lava contact");
        assertEquals(eyeWater, p.eyeInWater, context + " water eye");
        assertEquals(eyeLava, p.eyeInLava, context + " lava eye");
        assertEquals(oldEye, p.wasEyeInWater, context + " old eye");
        assertEquals(first, p.firstTick, context + " first tick");
        bits(xxa, p.xxa, context + " xxa");
        bits(yya, p.yya, context + " yya");
        bits(zza, p.zza, context + " zza");
    }

    @Test
    void connectedHorseMovementChargeAndPassenger() throws Exception {
        var registry = BlockRegistry.get();
        String path = "/vanilla/777/horse-whole-player.bin.gz";
        var raw = getClass().getResourceAsStream(path);
        assertNotNull(raw, path);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414857, in.readInt());
            assertEquals(2, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            assertEquals(36, in.readInt());
            int ticks = 0;
            for (int scenario = 0; scenario < 36; scenario++) {
                var kind = HorseState.Kind.values()[in.readUnsignedByte()];
                var blocks = new ArrayList<LocalTickOracle.Block>();
                for (int n = in.readInt(); n > 0; n--) blocks.add(new LocalTickOracle.Block(in.readInt(), in.readInt(), in.readInt(), in.readInt()));
                float width = in.readFloat(), height = in.readFloat(), eye = in.readFloat();
                var standing = new HorseOracle.Dimensions(in.readFloat(), in.readFloat(), in.readFloat());
                boolean controlled = in.readBoolean(), pushed = in.readBoolean();
                float scale = in.readFloat();
                int maxHeadYaw = in.readInt();
                boolean baby = in.readBoolean(), navigationCanFloat = in.readBoolean();
                var body = PistonPhysicsOracleTest.state(in);
                assertEquals(0, in.readInt());
                body.fixedBodyDimensions = true;
                body.bodyWidth = width;
                body.bodyHeight = height;
                body.firstTick = false;
                body.fluidShapes = FluidShapeCache.empty();
                var graph = new PassengerGraph();
                graph.spawn(1, true);
                graph.spawn(2);
                graph.startRiding(1, 2, true);
                var state = new HorseState(graph.get(2).generation(), kind, body);
                HorseOracle.assign(state,HorseTickOracleTest.state(in, kind == HorseState.Kind.CAMEL));
                body.yRot = state.body.yRot;
                body.xRot = state.body.xRot;
                extra(in, state, true, "initial");
                var own = BoatPlayerOracleTest.Own.read(in);
                var player = own.player(false);
                var local = own.hidden();
                player.fluidShapes = FluidShapeCache.empty();
                var env = LocalPlayerFluidOracleTest.environment(registry, blocks);
                var physics = HorseOracle.body(env, state, eye, pushed, controlled, true, false, standing);
                var f = new HorseOracle.Control[] { null };
                var defaultSeat = new Vec3d[] { Vec3d.ZERO };
                var ownAttachment = new Vec3d[] { Vec3d.ZERO };
                var ride = new HorsePlayerRide(graph, 1, 2, physics, () -> new HorsePassengerPhysics.Facts(kind, body.position, body.yRot, state.bodyYaw, body.height(), scale, baby, false, 0, 1, false, f[0].gameTime(), state.lastPoseChangeTick, state.oldStandAnimation, defaultSeat[0]), () -> new VehiclePacketState(state.generation, Tri.TRUE, body.position, body.yRot, body.xRot, null, false, body.onGround), p -> ownAttachment[0]);
                var playerData = new ClientPlayerData();
                var simulation = new HorsePlayerTick(env, physics, ride, player, local, playerData);
                for (int count = in.readInt(), t = 0; t < count; t++) {
                    boolean loaded = in.readBoolean();
                    var keys = PlayerInput.fromMask(in.readUnsignedByte());
                    player.yRot = in.readFloat();
                    player.xRot = in.readFloat();
                    f[0] = effective(facts(in, kind), in.readBoolean());
                    assertEquals(Set.of(), HorseOracle.simulate(simulation,state,f[0],maxHeadYaw,true,false,navigationCanFloat));
                    String context = "26.3 " + kind + " scenario " + scenario + " tick " + t;
                    PistonPhysicsOracleTest.compare(PistonPhysicsOracleTest.state(in), body, context + " body");
                    assertEquals(in.readInt(), body.movementThisTick.size(), context);
                    tail(in, state, context + " tail");
                    extra(in, state, false, context);
                    defaultSeat[0] = PistonPhysicsOracleTest.vector(in);
                    f[0] = effective(facts(in, kind), in.readBoolean());
                    boolean jumpable = in.readBoolean();
                    int cooldown = in.readInt();
                    ownAttachment[0] = PistonPhysicsOracleTest.vector(in);
                    assertEquals(jumpable, ride.jumpableVehicle(), context + " jumpable");
                    assertEquals(cooldown, ride.vehicleJumpCooldown(), context + " cooldown");
                    playerData.clientLoaded = loaded;
                    var result = simulation.simulatePassengerTick(keys);
                    {
                        var late = simulation.sendAfterEntityTicks();
                        result = new TickPackets(result.then(late).packets(), late.unmodeled());
                    }
                    BoatPlayerOracleTest.Own.read(in).compare(player, local, context + " passenger");
                    tail(in, state, context + " charge");
                    var packets = new ArrayList<InboundEvent>();
                    for (int n = in.readInt(); n > 0; n--) packets.add(LocalPlayerOracleCodec.packet(in));
                    assertEquals(packets, Wire.in(result.packets()), context + " packets");
                    assertEquals(Set.of(), result.unmodeled(), context + " tick gaps");
                    ticks++;
                }
            }
            assertEquals(1152, ticks);
            assertEquals(-1, in.read());
        }
    }
}
