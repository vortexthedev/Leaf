package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.player.state.ClientPlayerData;
import ru.minecomplex.mac.Wire;
import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.entity.PassengerGraph;
import ru.minecomplex.mac.entity.Pose;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
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
 * Real boat tick and mounted LocalPlayer tick stay chained; no expected after-state feeds either solver.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class BoatPlayerOracleTest {

    record Own(LocalTickOracle.State local, LocalTickOracle.Fluid fluid, float yawOld, float head, float body, boolean hands, Vec3d oldPosition, boolean firstTick, boolean noPhysics) {

        static Own read(DataInputStream in) throws Exception {
            return new Own(LocalTickOracle.State.read(in), LocalTickOracle.Fluid.read(in), in.readFloat(), in.readFloat(), in.readFloat(), in.readBoolean(), BoatInterpolationOracleTest.vector(in), in.readBoolean(), in.readBoolean());
        }

        PlayerState player(boolean mayfly) {
            var b = local.base();
            var p = b.player().toState();
            p.passenger = true;
            p.pose = Pose.valueOf(b.pose());
            p.swimming = b.swimming();
            p.fallFlying = b.fallFlying();
            p.abilities.mayfly = mayfly;
            p.abilities.flying = b.flying();
            p.xxa = b.xxa();
            p.zza = b.zza();
            fluid.apply(p);
            p.oldPosition = oldPosition;
            p.firstTick = firstTick;
            p.noPhysics = noPhysics;
            return p;
        }

        LocalPlayerState hidden() {
            var b = local.base();
            var l = new LocalPlayerState();
            l.input = b.input();
            l.vector = b.vector();
            l.crouching = b.crouching();
            l.wasFallFlying = local.wasFallFlying();
            l.jumpTriggerTime = b.jumpTriggerTime();
            l.sprintTriggerTime = b.sprintTriggerTime();
            l.autoJumpTime = b.autoJumpTime();
            l.jumpRidingScale = local.ridingScale();
            l.jumpRidingTicks = local.ridingTicks();
            l.waterVisionTime = local.waterVision();
            l.send = b.sendState();
            l.send.wasShiftKeyDown = local.wasShift();
            l.yawOld = yawOld;
            l.headYaw = head;
            l.bodyYaw = body;
            l.handsBusy = hands;
            return l;
        }

        void compare(PlayerState p, LocalPlayerState l, String context) {
            var b = local.base();
            assertEquals(List.of(), b.player().differences(MovementOracleTest.Recorded.of(p)), context);
            assertEquals(b.input(), l.input, context);
            bits(b.vector().x(), l.vector.x(), context);
            bits(b.vector().y(), l.vector.y(), context);
            bits(b.xxa(), p.xxa, context);
            bits(b.zza(), p.zza, context);
            assertEquals(b.pose(), p.pose.name(), context);
            assertEquals(b.swimming(), p.swimming, context);
            assertEquals(b.fallFlying(), p.fallFlying, context);
            assertEquals(b.flying(), p.abilities.flying, context);
            assertEquals(b.crouching(), l.crouching, context);
            assertEquals(b.jumpTriggerTime(), l.jumpTriggerTime, context);
            assertEquals(b.sprintTriggerTime(), l.sprintTriggerTime, context);
            assertEquals(b.autoJumpTime(), l.autoJumpTime, context);
            assertEquals(local.wasFallFlying(), l.wasFallFlying, context);
            assertEquals(local.ridingTicks(), l.jumpRidingTicks, context);
            bits(local.ridingScale(), l.jumpRidingScale, context);
            assertEquals(local.waterVision(), l.waterVisionTime, context);
            assertEquals(local.wasShift(), l.send.wasShiftKeyDown, context);
            bits(yawOld, l.yawOld, context + " old yaw");
            bits(head, l.headYaw, context + " head");
            bits(body, l.bodyYaw, context + " body");
            assertEquals(hands, l.handsBusy, context + " hands");
            PistonPhysicsOracleTest.bits(oldPosition.x(), p.oldPosition.x(), context + " old x");
            PistonPhysicsOracleTest.bits(oldPosition.y(), p.oldPosition.y(), context + " old y");
            PistonPhysicsOracleTest.bits(oldPosition.z(), p.oldPosition.z(), context + " old z");
            assertEquals(firstTick, p.firstTick, context + " first tick");
            assertEquals(noPhysics, p.noPhysics, context + " no physics");
            PistonPhysicsOracleTest.bits(fluid.waterHeight(), p.waterHeight, context);
            PistonPhysicsOracleTest.bits(fluid.lavaHeight(), p.lavaHeight, context);
            assertEquals(fluid.water(), p.wasTouchingWater, context);
            assertEquals(fluid.lava(), p.isInLava, context);
            assertEquals(fluid.eyeWater(), p.eyeInWater, context);
            assertEquals(fluid.eyeLava(), p.eyeInLava, context);
            assertEquals(fluid.wasEyeWater(), p.wasEyeInWater, context);
            assertEquals(fluid.underwater(), p.wasUnderwater, context);
            assertEquals(b.lastSentInput(), l.send.lastSentInput, context);
            assertEquals(b.wasSprinting(), l.send.wasSprinting, context);
            PistonPhysicsOracleTest.bits(b.xLast(), l.send.xLast, context);
            PistonPhysicsOracleTest.bits(b.yLast(), l.send.yLast, context);
            PistonPhysicsOracleTest.bits(b.zLast(), l.send.zLast, context);
            bits(b.yRotLast(), l.send.yRotLast, context);
            bits(b.xRotLast(), l.send.xRotLast, context);
            assertEquals(b.positionReminder(), l.send.positionReminder, context);
            assertEquals(b.lastOnGround(), l.send.lastOnGround, context);
            assertEquals(b.lastHorizontalCollision(), l.send.lastHorizontalCollision, context);
            assertEquals(b.autoJumpEnabled(), l.send.autoJumpEnabled, context);
        }
    }

    static void bits(float expected, float actual, String context) {
        assertEquals(Float.floatToIntBits(expected), Float.floatToIntBits(actual), context);
    }

    @Test
    void chainedRootBoatAndLocalPassenger() throws Exception {
        var registry = BlockRegistry.get();
        String path = "/vanilla/777/boat-player.bin.gz";
        var stream = getClass().getResourceAsStream(path);
        assertNotNull(stream, path);
        try (var in = new DataInputStream(new GZIPInputStream(stream))) {
            assertEquals(0x4d415242, in.readInt());
            assertEquals(3, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            int scenarios = in.readInt();
            assertEquals(48, scenarios);
            int ticks = 0;
            for (int s = 0; s < scenarios; s++) {
                boolean raft = in.readBoolean(), chest = in.readBoolean(), controller = in.readBoolean(), mayfly = in.readBoolean();
                var blocks = new ArrayList<LocalTickOracle.Block>();
                for (int n = in.readInt(); n > 0; n--) blocks.add(new LocalTickOracle.Block(in.readInt(), in.readInt(), in.readInt(), in.readInt()));
                float width = in.readFloat(), height = in.readFloat(), eye = in.readFloat();
                var initial = BoatTickOracleTest.Recorded.read(in);
                var own = Own.read(in);
                var graph = new PassengerGraph();
                graph.spawn(1, true);
                graph.spawn(2);
                graph.spawn(3, true);
                if (!controller)
                    graph.startRiding(3, 2, true);
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
                var player = own.player(mayfly);
                player.fluidShapes = FluidShapeCache.empty();
                var local = own.hidden();
                var env = LocalPlayerFluidOracleTest.environment(registry, blocks);
                // The resolved attachment is read at the seat boundary, not from a Bukkit player.
                var attachment = new Vec3d[] { Vec3d.ZERO };
                var playerData = new ClientPlayerData();
                playerData.food = own.local().food();
                var simulation = new BoatPlayerTick(env, graph, 1, 2, boat, player, local, playerData, raft, chest, p -> attachment[0], box -> List.of());
                for (int count = in.readInt(), t = 0; t < count; t++) {
                    boolean loaded = in.readBoolean();
                    var input = PlayerInput.fromMask(in.readUnsignedByte());
                    player.yRot = in.readFloat();
                    player.xRot = in.readFloat();
                    attachment[0] = BoatInterpolationOracleTest.vector(in);
                    boolean authority = in.readBoolean(), controlled = in.readBoolean();
                    playerData.clientLoaded = loaded;
                    var result = simulation.simulateEntityTicks(input, authority ? Tri.TRUE : Tri.FALSE, controlled, true);
                    if (in.readBoolean()) {
                        boat.body.position = BoatInterpolationOracleTest.vector(in);
                        boat.body.yRot = in.readFloat();
                    }
                    {
                        result = result.then(simulation.sendAfterEntityTicks());
                    }
                    String context = "26.3 scenario " + s + " tick " + t;
                    BoatTickOracleTest.Recorded.read(in).compare(boat, context + " boat");
                    Own.read(in).compare(player, local, context + " player");
                    assertEquals(in.readBoolean(), boat.input.left(), context);
                    assertEquals(in.readBoolean(), boat.input.right(), context);
                    assertEquals(in.readBoolean(), boat.input.forward(), context);
                    assertEquals(in.readBoolean(), boat.input.backward(), context);
                    var packets = new ArrayList<InboundEvent>();
                    for (int n = in.readInt(); n > 0; n--) packets.add(LocalPlayerOracleCodec.packet(in));
                    assertEquals(packets, Wire.in(result.packets()), context + " packets");
                    assertEquals(Set.of(), result.unmodeled(), context + " gaps");
                    ticks++;
                }
            }
            assertEquals(1152, ticks);
            assertEquals(-1, in.read());
        }
    }
}
