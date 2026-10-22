package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.player.state.ClientPlayerData;
import ru.minecomplex.mac.Wire;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.entity.EntityAttachment;
import ru.minecomplex.mac.entity.EntityTypes;
import ru.minecomplex.mac.entity.PassengerGraph;
import ru.minecomplex.mac.entity.Pose;
import ru.minecomplex.mac.entity.attribute.Attribute;
import ru.minecomplex.mac.entity.attribute.AttributeMap;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.world.BlockTag;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.world.ResolvedBlockTags;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.entity.LivingVehicleState;
import ru.minecomplex.mac.mechanics.fluid.FluidContactOracleTest;
import ru.minecomplex.mac.mechanics.fluid.FluidShapeCache;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;

/**
 * MALW (LivingWholePlayerSamples): real complete pig/strider/happy-ghast/nautilus ticks and the mounted LocalPlayer.
 * Dimensions, seats and the player's vehicle attachment come from MAET; attributes from the type's supplier with the
 * fixture's bases; the walk animation, cold state, rotation control and packets are compared bit-exact every tick.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class LivingWholePlayerOracleTest {

    static final String[] KEYS = { "minecraft:pig", "minecraft:strider", "minecraft:happy_ghast", "minecraft:nautilus", "minecraft:zombie_nautilus" };

    static final Attribute[] ATTRIBUTES = { Attribute.MOVEMENT_SPEED, Attribute.FLYING_SPEED, Attribute.STEP_HEIGHT, Attribute.GRAVITY, Attribute.SCALE, Attribute.MOVEMENT_EFFICIENCY, Attribute.WATER_MOVEMENT_EFFICIENCY, Attribute.SAFE_FALL_DISTANCE, Attribute.JUMP_STRENGTH };

    static void bits(float e, float a, String s) {
        assertEquals(Float.floatToIntBits(e), Float.floatToIntBits(a), s);
    }

    /**
     * The recorded vehicle; on the initial record it seeds the state, afterwards it is compared.
     */
    static void vehicle(DataInputStream in, LivingVehicleState state, int kind, boolean init, String context) throws Exception {
        var body = PistonPhysicsOracleTest.state(in);
        int recordings = in.readInt();
        var control = OtherVehicleOracleTest.state(in, kind, state.control.boostDuration);
        int stable = in.readInt();
        float head = in.readFloat();
        int delay = in.readInt();
        double water = in.readDouble(), lava = in.readDouble();
        boolean wet = in.readBoolean(), hot = in.readBoolean(), eyeWater = in.readBoolean(), eyeLava = in.readBoolean();
        boolean oldEye = in.readBoolean(), first = in.readBoolean();
        float xxa = in.readFloat(), yya = in.readFloat(), zza = in.readFloat();
        boolean sprint = in.readBoolean(), swim = in.readBoolean(), jump = in.readBoolean();
        float walkOld = in.readFloat(), walk = in.readFloat(), walkPosition = in.readFloat(), walkScale = in.readFloat();
        boolean suffocating = in.readBoolean();
        double speed = in.readDouble();
        var p = state.body;
        if (init) {
            p.position = body.position;
            p.deltaMovement = body.deltaMovement;
            p.stuckSpeedMultiplier = body.stuckSpeedMultiplier;
            p.onGround = body.onGround;
            p.horizontalCollision = body.horizontalCollision;
            p.verticalCollision = body.verticalCollision;
            p.verticalCollisionBelow = body.verticalCollisionBelow;
            p.minorHorizontalCollision = body.minorHorizontalCollision;
            p.onGroundNoBlocks = body.onGroundNoBlocks;
            p.isInPowderSnow = body.isInPowderSnow;
            p.fallDistance = body.fallDistance;
            assertEquals(0, recordings);
            control.boostDuration = state.control.boostDuration;
            state.control = control;
            p.yRot = control.yaw;
            p.xRot = control.pitch;
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
            state.walkSpeedOld = walkOld;
            state.walkSpeed = walk;
            state.walkPosition = walkPosition;
            state.walkPositionScale = walkScale;
            state.suffocating = suffocating;
            return;
        }
        PistonPhysicsOracleTest.compare(body, p, context + " body");
        assertEquals(recordings, p.movementThisTick.size(), context + " movement recordings");
        var s = state.control;
        // the control mirrors the body; yaw fields follow the body's rotation
        OtherVehicleOracleTest.compare(new java.io.DataInputStream(new java.io.ByteArrayInputStream(encode(control, kind))), s, kind, s.boostDuration, context + " control");
        assertEquals(stable, state.bodyRotation.headStableTime, context + " stable time");
        bits(head, state.bodyRotation.lastStableHeadYaw, context + " stable yaw");
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
        assertEquals(sprint, p.sprinting, context + " sprint");
        assertEquals(swim, p.swimming, context + " swim");
        assertEquals(jump, p.jumping, context + " jumping");
        bits(walkOld, state.walkSpeedOld, context + " walk speed old");
        bits(walk, state.walkSpeed, context + " walk speed");
        bits(walkPosition, state.walkPosition, context + " walk position");
        bits(walkScale, state.walkPositionScale, context + " walk scale");
        assertEquals(suffocating, state.suffocating, context + " suffocating");
        FluidContactOracleTest.bits(speed, p.attributes.value(Attribute.MOVEMENT_SPEED), context + " movement speed");
    }

    /**
     * Re-encodes a read control state in the MAOV layout, so the existing comparison applies.
     */
    static byte[] encode(OtherVehicleRiding.State s, int kind) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        var out = new java.io.DataOutputStream(bytes);
        out.writeDouble(s.movement.x());
        out.writeDouble(s.movement.y());
        out.writeDouble(s.movement.z());
        for (float f : new float[] { s.yaw, s.pitch, s.yawOld, s.headYaw, s.bodyYaw, s.speed }) out.writeFloat(f);
        out.writeBoolean(s.onGround);
        out.writeBoolean(s.needsSync);
        if (kind < 2) {
            out.writeBoolean(s.boosting);
            out.writeInt(s.boostTime);
        }
        if (kind >= 3) {
            out.writeFloat(s.pendingJump);
            out.writeBoolean(s.jumping);
            out.writeBoolean(s.dashing);
            out.writeInt(s.dashCooldown);
        }
        return bytes.toByteArray();
    }

    @Test
    void wholeLivingVehicleTicks() throws Exception {
        var registry = BlockRegistry.get();
        var types = EntityTypes.get();
        String path = "/vanilla/777/living-whole-player.bin.gz";
        var raw = getClass().getResourceAsStream(path);
        assertNotNull(raw, path);
        int ticks = 0, expectedTicks;
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414c57, in.readInt());
            assertEquals(2, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            int mask = in.readInt();
            int count = in.readInt();
            assertEquals(Integer.bitCount(mask) * 6, count);
            expectedTicks = count * 32;
            assertEquals(3 | (4) | (24), mask);
            for (int n = 0; n < count; n++) {
                int kind = in.readUnsignedByte(), scenario = in.readUnsignedByte();
                var vehicleKind = OtherVehicleRiding.Kind.values()[kind];
                var blocks = new ArrayList<LocalTickOracle.Block>();
                for (int b = in.readInt(); b > 0; b--) blocks.add(new LocalTickOracle.Block(in.readInt(), in.readInt(), in.readInt(), in.readInt()));
                float width = in.readFloat(), height = in.readFloat(), eye = in.readFloat();
                boolean baby = in.readBoolean();
                float seatScale = in.readFloat();
                int maxHeadYaw = in.readInt();
                boolean pushed = in.readBoolean(), canFloat = in.readBoolean();
                var rideable = types.rideable(KEYS[kind]);
                assertNotNull(rideable, KEYS[kind]);
                String context0 = "26.3 " + vehicleKind + " scenario " + scenario;
                assertEquals(maxHeadYaw, rideable.maxHeadYRot(), context0 + " head yaw");
                assertEquals(pushed, rideable.pushedByFluid(), context0 + " fluid push");
                assertEquals(canFloat, rideable.canFloat(), context0 + " float");
                var attributes = AttributeMap.forEntity(types.type(KEYS[kind]).defaultAttributes());
                for (Attribute attribute : ATTRIBUTES) {
                    if (in.readBoolean())
                        attributes.get(attribute).setBaseValue(in.readDouble());
                }
                float scale = rideable.scale(attributes.value(Attribute.SCALE));
                var dimensions = types.livingDimensions(rideable, baby, Pose.STANDING, scale);
                bits(width, dimensions.width(), context0 + " width");
                bits(height, dimensions.height(), context0 + " height");
                bits(eye, dimensions.eyeHeight(), context0 + " eye");
                bits(seatScale, scale * rideable.ageScale(baby), context0 + " seat scale");
                var body = new PlayerState();
                body.fixedBodyDimensions = true;
                body.bodyWidth = width;
                body.bodyHeight = height;
                body.fluidShapes = FluidShapeCache.empty();
                body.attributes = attributes;
                var graph = new PassengerGraph();
                graph.spawn(1, true);
                graph.spawn(2);
                graph.startRiding(1, 2, true);
                var state = new LivingVehicleState(graph.get(2).generation(), vehicleKind, body);
                vehicle(in, state, kind, true, context0);
                var own = BoatPlayerOracleTest.Own.read(in);
                var player = own.player(false);
                var local = own.hidden();
                player.fluidShapes = FluidShapeCache.empty();
                var plain = LocalPlayerFluidOracleTest.environment(registry, blocks);
                var tags = new ResolvedBlockTags(Map.of(BlockTag.STRIDER_WARM_BLOCKS, new int[] { registry.block("minecraft:lava").registryId() }));
                MovementEnvironment env = new MovementEnvironment() {

                    public boolean fluidsResolved() {
                        return true;
                    }

                    public Tri fastLava() {
                        return Tri.FALSE;
                    }

                    public BlockSource blocks() {
                        return plain.blocks();
                    }

                    public BlockRegistry registry() {
                        return registry;
                    }

                    public BlockTags tags() {
                        return tags;
                    }

                    public boolean hasChunkAt(int x, int z) {
                        return true;
                    }

                    public int minY() {
                        return -64;
                    }

                    public List<VoxelShape> entityCollisions(Aabb box) {
                        return List.of();
                    }

                    public VoxelShape borderShape(Aabb box) {
                        return null;
                    }
                };
                var bodyPhysics = new LivingVehicleBodyPhysics(env, body, vehicleKind, eye, pushed, true, true, false, attributes.value(Attribute.FLYING_SPEED));
                var vehicleTick = new LivingVehicleTickPhysics(state, bodyPhysics);
                boolean effectiveAi = true;
                var facts = new LivingVehicleTickPhysics.Facts(true, true, effectiveAi, false, baby, maxHeadYaw, true, false, canFloat);
                var ride = new LivingVehiclePlayerRide(graph, 1, 2, state, vehicleTick.control(), () -> vehicleTick.controlFacts(facts), index -> types.livingDimensions(rideable, baby, Pose.STANDING, scale).attachments().getClamped(EntityAttachment.PASSENGER, index, state.body.yRot), () -> scale * rideable.ageScale(baby), () -> new VehiclePacketState(state.generation, Tri.TRUE, body.position, body.yRot, body.xRot, null, false, body.onGround), rider -> types.playerDimensions(rider.pose, rider.dimensionScale).attachments().get(EntityAttachment.VEHICLE, 0, rider.yRot));
                var playerData = new ClientPlayerData();
                playerData.food = own.local().food();
                var simulation = new LivingVehiclePlayerTick(env, state, vehicleTick, ride, player, local, playerData);
                for (int count2 = in.readInt(), t = 0; t < count2; t++) {
                    boolean loaded = in.readBoolean();
                    var keys = PlayerInput.fromMask(in.readUnsignedByte());
                    player.yRot = in.readFloat();
                    player.xRot = in.readFloat();
                    String context = context0 + " tick " + t;
                    int boost = in.readInt();
                    if (boost >= 0)
                        vehicleTick.control().boostSynced(state.control, boost);
                    assertEquals(effectiveAi, in.readBoolean(), context + " effective AI");
                    var gaps = simulation.simulateVehicleTick(facts);
                    vehicle(in, state, kind, false, context);
                    var defaultSeat = PistonPhysicsOracleTest.vector(in);
                    var derivedSeat = types.livingDimensions(rideable, baby, Pose.STANDING, scale).attachments().getClamped(EntityAttachment.PASSENGER, 0, state.body.yRot);
                    for (var axis : ru.minecomplex.mac.collision.Axis.values()) FluidContactOracleTest.bits(defaultSeat.get(axis), derivedSeat.get(axis), context + " seat " + axis);
                    boolean jumpable = in.readBoolean();
                    int cooldown = in.readInt();
                    var vehicleAttachment = PistonPhysicsOracleTest.vector(in);
                    var derivedAttachment = types.playerDimensions(player.pose, player.dimensionScale).attachments().get(EntityAttachment.VEHICLE, 0, player.yRot);
                    for (var axis : ru.minecomplex.mac.collision.Axis.values()) FluidContactOracleTest.bits(vehicleAttachment.get(axis), derivedAttachment.get(axis), context + " attachment " + axis);
                    assertEquals(Set.of(), gaps, context + " vehicle gaps");
                    boolean nautilus = vehicleKind == OtherVehicleRiding.Kind.NAUTILUS || vehicleKind == OtherVehicleRiding.Kind.ZOMBIE_NAUTILUS;
                    assertEquals(nautilus, jumpable, context + " jumpable");
                    assertEquals(nautilus ? state.control.dashCooldown : 0, cooldown, context + " cooldown");
                    assertEquals(jumpable, ride.jumpableVehicle(), context + " ride jumpable");
                    assertEquals(cooldown, ride.vehicleJumpCooldown(), context + " ride cooldown");
                    playerData.clientLoaded = loaded;
                    var result = simulation.simulatePassengerTick(keys);
                    var packets = new ArrayList<InboundEvent>(Wire.in(result.packets()));
                    var unmodeled = new java.util.HashSet<>(result.unmodeled());
                    {
                        var late = simulation.sendAfterEntityTicks();
                        packets.addAll(Wire.in(late.packets()));
                        unmodeled.addAll(late.unmodeled());
                    }
                    BoatPlayerOracleTest.Own.read(in).compare(player, local, context + " passenger");
                    var after = OtherVehicleOracleTest.state(in, kind, state.control.boostDuration);
                    assertEquals(after.pendingJump, state.control.pendingJump, context + " charge");
                    var expected = new ArrayList<InboundEvent>();
                    for (int p = in.readInt(); p > 0; p--) expected.add(LocalPlayerOracleCodec.packet(in));
                    assertEquals(expected, packets, context + " packets");
                    assertEquals(Set.of(), unmodeled, context + " tick gaps");
                    ticks++;
                }
            }
            assertEquals(-1, in.read());
        }
        assertEquals(expectedTicks, ticks);
    }
}
