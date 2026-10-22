package ru.minecomplex.mac.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.mechanics.collision.BlockCollisions;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class SuffocationDifferentialTest {
    private DataInputStream open(String name) throws Exception {
        var stream = getClass().getResourceAsStream("/vanilla/777/" + name + ".bin.gz");
        assertNotNull(stream);
        return new DataInputStream(new GZIPInputStream(stream));
    }

    @Test
    void everyStateMatchesVanillaWithAndWithoutTags() throws Exception {
        BlockRegistry registry = BlockRegistry.get();
        BlockTags all = (tag, block) -> tag == BlockTag.CAUSES_SUFFOCATION;
        try (var in = open("suffocation")) {
            assertEquals(0x4D414346, in.readInt());
            assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            assertEquals(registry.stateCount(), in.readInt());
            int dynamic = 0;
            for (int state = 0; state < registry.stateCount(); state++) {
                boolean none = in.readBoolean();
                boolean tagged = in.readBoolean();
                String context = "" + registry.block(state).name() + " " + registry.properties(state);
                if (registry.has(state, BlockRegistry.FLAG_BLOCK_ENTITY_SUFFOCATION)) {
                    assertEquals(Tri.EITHER, registry.suffocation(state, BlockTags.NONE), context);
                    assertTrue(none && tagged, "absent shulker block entity: " + context);
                    dynamic++;
                } else {
                    assertEquals(none ? Tri.TRUE : Tri.FALSE, registry.suffocation(state, BlockTags.NONE), context);
                    assertEquals(tagged ? Tri.TRUE : Tri.FALSE, registry.suffocation(state, all), context);
                }
            }
            assertTrue(dynamic > 0, "unresolved shulker entities must remain uncertain");
            assertEquals(-1, in.read());
        }
    }

    @Test
    void suffocatingIterationMatchesVanilla() throws Exception {
        BlockRegistry registry = BlockRegistry.get();
        try (var in = open("suffocating-collisions")) {
            assertEquals(3000, in.readInt());
            int nonempty = 0;
            for (int sample = 0; sample < 3000; sample++) {
                int x0 = in.readInt(), y0 = in.readInt(), z0 = in.readInt();
                int[] states = new int[64];
                for (int i = 0; i < states.length; i++) states[i] = in.readInt();
                Aabb box = new Aabb(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble());
                List<String> expected = new ArrayList<>();
                int count = in.readInt();
                for (int i = 0; i < count; i++) expected.add(in.readInt() + "," + in.readInt() + "," + in.readInt());
                BlockSource world = (x, y, z) -> x >= x0 && x < x0 + 4 && y >= y0 && y < y0 + 4 && z >= z0 && z < z0 + 4
                        ? states[(x - x0) * 16 + (y - y0) * 4 + z - z0] : 0;
                List<String> actual = new ArrayList<>();
                // The fixture has no block entities; vanilla's shulker predicate is true in their absence.
                BlockCollisions.collect(world, registry, BlockCollisions.emptyContext(registry), box, true,
                        (state, x, y, z) -> registry.suffocation(state, BlockTags.NONE) != Tri.FALSE,
                        (x, y, z, shape) -> actual.add(x + "," + y + "," + z));
                assertEquals(expected, actual, "sample " + sample);
                if (count > 0) nonempty++;
            }
            assertTrue(nonempty > 10, "oracle must reach suffocating collisions: " + nonempty);
            assertEquals(-1, in.read());
        }
    }
}
