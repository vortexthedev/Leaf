package ru.minecomplex.mac.world.shulker;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.*;
import ru.minecomplex.mac.world.*;
import static org.junit.jupiter.api.Assertions.*;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.engine.blockeffects.MovementBlocks;
import ru.minecomplex.mac.engine.movementtick.MovementTicker;
import ru.minecomplex.mac.engine.movementtick.PistonPhysicsOracleTest;
import ru.minecomplex.mac.mechanics.collision.BlockCollisions;
import ru.minecomplex.mac.mechanics.collision.EntityShapeContext;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.world.piston.PistonProperties;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class ShulkerOracleTest {

    static Aabb box(DataInputStream in) throws IOException { return new Aabb(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble()); }
    static void compare(Aabb expected, Aabb actual, String context) {
        for (Axis axis : Axis.values()) {
            PistonPhysicsOracleTest.bits(expected.min(axis), actual.min(axis), context + " min " + axis);
            PistonPhysicsOracleTest.bits(expected.max(axis), actual.max(axis), context + " max " + axis);
        }
    }
    @Test
    void matchesVanilla() throws Exception {
         var registry = BlockRegistry.get();
        var raw = getClass().getResourceAsStream("/vanilla/777/shulker-integration.bin.gz"); assertNotNull(raw);
        var world = new CompensatedWorld("minecraft:overworld", 0, 80, 2, registry);
        var sections = new PalettedSection[5]; for (int n = 0; n < 5; n++) sections[n] = PalettedSection.single(0, 16);
        var column = new ChunkColumn(0, 0, sections); world.load(column);
        var resolveClock = CompensatedWorld.class.getDeclaredMethod("resolveInitialPistonClock"); resolveClock.setAccessible(true); resolveClock.invoke(world);
        var entriesField = CompensatedWorld.class.getDeclaredField("shulkers"); entriesField.setAccessible(true);
        @SuppressWarnings("unchecked") var entries = (Map<BlockPosition, Object>) entriesField.get(world);
        var entryClass = Class.forName("ru.minecomplex.mac.replica.CompensatedWorld$ShulkerEntry");
        var entryConstructor = entryClass.getDeclaredConstructors()[0]; entryConstructor.setAccessible(true);
        MovementEnvironment dynamicEnvironment = new MovementEnvironment() {
            public BlockSource blocks() { return world; } public BlockRegistry registry() { return registry; }
            public BlockTags tags() { return BlockTags.NONE; } public boolean hasChunkAt(int x, int z) { return true; }
            public int minY() { return 0; } public List<VoxelShape> entityCollisions(Aabb box) { return List.of(); }
            public VoxelShape borderShape(Aabb box) { return null; }
        };
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414853, in.readInt()); assertEquals(1, in.readUnsignedShort()); assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt()); assertEquals(4096, in.readInt());
            for (int i = 0; i < 4096; i++) {
                var direction = BlockDirection.values()[in.readInt()]; float progress = in.readFloat(), old = in.readFloat();
                var lid = new ShulkerAnimation(1, ShulkerAnimation.Status.OPENING, progress, old);
                Aabb expectedBounds = box(in);
                compare(expectedBounds, ShulkerGeometry.bounds(direction, lid), "26.3 bounds " + i);
                int state = PistonProperties.get().with(PistonProperties.get().defaultState("minecraft:shulker_box"), "facing", direction.name().toLowerCase(Locale.ROOT));
                world.setBlock(0, 64, 0, state);
                entries.put(new BlockPosition(0, 64, 0), entryConstructor.newInstance(lid, new Object()));
                var context = new EntityShapeContext(MovementBlocks.of(registry), new PlayerState(), gap -> {}, world, null);
                var shape = context.shape(state, 0, 64, 0);
                var emptyShape = BlockCollisions.emptyContext(registry,world,gap -> {}).shape(state,0,64,0);
                if (expectedBounds.maxX() - expectedBounds.minX() < 1.0E-7 || expectedBounds.maxY() - expectedBounds.minY() < 1.0E-7 || expectedBounds.maxZ() - expectedBounds.minZ() < 1.0E-7) assertTrue(shape.isEmpty());
                else compare(expectedBounds, shape.toAabbs().getFirst(), "26.3 integrated bounds " + i);
                if (shape.isEmpty()) assertTrue(emptyShape.isEmpty());
                else compare(expectedBounds,emptyShape.toAabbs().getFirst(),"26.3 empty context bounds " + i);
                assertEquals(ru.minecomplex.mac.util.Tri.FALSE, dynamicEnvironment.suffocation(state, 0, 64, 0));
                Aabb area = ShulkerGeometry.delta(direction, old, progress, new Vec3d(0.5, 64.0, 0.5));
                compare(box(in), area, "26.3 query " + i);
                boolean ignored = in.readBoolean(); Aabb entityBox = box(in); var moves = new ArrayList<Vec3d>();
                var entity = new ShulkerPush.Entity() {
                    public boolean ignored() { return ignored; } public void move(Vec3d delta) { moves.add(delta); }
                };
                ShulkerPush.push(new BlockPosition(0, 64, 0), direction, lid, query -> query.intersects(entityBox.minX(), entityBox.minY(), entityBox.minZ(), entityBox.maxX(), entityBox.maxY(), entityBox.maxZ()) ? List.of(entity) : List.of());
                assertEquals(in.readInt(), moves.size(), "26.3 pushes " + i);
                for (var move : moves) for (Axis axis : Axis.values()) PistonPhysicsOracleTest.bits(in.readDouble(), move.get(axis), "26.3 push " + i);
            }
            assertEquals(48, in.readInt());
            for (int scenario = 0; scenario < 48; scenario++) {
                var map = new HashMap<BlockPosition, Integer>();
                for (int n = in.readInt(); n > 0; n--) map.put(new BlockPosition(in.readInt(), in.readInt(), in.readInt()), in.readInt());
                MovementEnvironment env = new MovementEnvironment() {
                    public BlockSource blocks() { return (x, y, z) -> map.getOrDefault(new BlockPosition(x, y, z), 0); }
                    public BlockRegistry registry() { return registry; } public BlockTags tags() { return BlockTags.NONE; }
                    public boolean hasChunkAt(int x, int z) { return true; } public int minY() { return -64; }
                    public List<VoxelShape> entityCollisions(Aabb box) { return List.of(); } public VoxelShape borderShape(Aabb box) { return null; }
                };
                PlayerState player = PistonPhysicsOracleTest.state(in); assertEquals(0, in.readInt()); player.oldPosition = player.position;
                var physics = new MovementTicker(env, player); assertEquals(18, in.readInt());
                for (int tick = 0; tick < 18; tick++) {
                    var direction = BlockDirection.values()[in.readInt()]; double amount = in.readDouble();
                    physics.shulkerEntity().move(new Vec3d(amount * direction.x, amount * direction.y, amount * direction.z));
                    String context = "26.3 displacement " + scenario + " tick " + tick;
                    PistonPhysicsOracleTest.compare(PistonPhysicsOracleTest.state(in), player, context);
                    assertEquals(in.readInt(), player.movementThisTick.size(), context + " recordings");
                    assertTrue(physics.unmodeled().isEmpty(), context + physics.unmodeled());
                }
            }
            assertEquals(-1, in.read());
        }
    }
}
