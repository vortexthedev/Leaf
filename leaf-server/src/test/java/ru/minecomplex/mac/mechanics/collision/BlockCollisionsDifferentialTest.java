package ru.minecomplex.mac.mechanics.collision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import ru.minecomplex.mac.vanilla.VanillaSamples;
import ru.minecomplex.mac.world.BlockOffsets;
import ru.minecomplex.mac.world.BlockRegistry;

/**
 * {@link BlockCollisions} and {@link BlockOffsets} against vanilla: which blocks, in which order, contribute shapes
 * for a box in a random 4×4×4 world (half of the boxes on or within ±1e-6 of block grid lines), and block offsets
 * at random positions.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class BlockCollisionsDifferentialTest {

    @Test
    void iterationMatchesVanilla() {
        BlockRegistry registry = BlockRegistry.get();
        BlockCollisions.ShapeContext context = BlockCollisions.emptyContext(registry);
        List<String> failures = new ArrayList<>();
        int nonEmpty = 0;
        List<VanillaSamples.Iteration> samples = VanillaSamples.get().iterations;
        for (int i = 0; i < samples.size(); i++) {
            VanillaSamples.Iteration sample = samples.get(i);
            List<int[]> ours = new ArrayList<>();
            BlockCollisions.collect(sample::stateAt, registry, context, sample.box(), (x, y, z, shape) -> ours.add(new int[]{x, y, z}));
            if (!sameSequence(sample.positions(), ours)) {
                failures.add("sample " + i + " box " + sample.box() + ": vanilla " + describe(sample.positions()) + ", ours " + describe(ours));
            }
            if (!sample.positions().isEmpty()) {
                nonEmpty++;
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + samples.size() + " differ, first: " + failures.getFirst());
        }
        assertTrue(nonEmpty > samples.size() / 3, "samples must collide with blocks, only " + nonEmpty + " did");
    }

    @Test
    void offsetsMatchVanilla() {
        BlockRegistry registry = BlockRegistry.get();
        List<VanillaSamples.Offset> samples = VanillaSamples.get().offsets;
        assertTrue(samples.size() > 500);
        for (VanillaSamples.Offset sample : samples) {
            double[] offset = BlockOffsets.offset(registry.block(sample.state()), sample.x(), sample.z());
            String where = registry.block(sample.state()).name() + " at " + sample.x() + "," + sample.y() + "," + sample.z();
            assertEquals(0, Double.compare(sample.dx(), offset[0]), where + " x");
            assertEquals(0, Double.compare(sample.dy(), offset[1]), where + " y");
            assertEquals(0, Double.compare(sample.dz(), offset[2]), where + " z");
        }
    }

    private static boolean sameSequence(List<int[]> a, List<int[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!Arrays.equals(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static String describe(List<int[]> positions) {
        StringBuilder text = new StringBuilder("[");
        for (int[] p : positions) {
            text.append(Arrays.toString(p));
        }
        return text.append("]").toString();
    }
}
