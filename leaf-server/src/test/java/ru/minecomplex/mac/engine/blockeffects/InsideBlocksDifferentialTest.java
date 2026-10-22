package ru.minecomplex.mac.engine.blockeffects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

import ru.minecomplex.mac.packet.NativeRuntime;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * {@link InsideBlocks#forEachBlockIntersectedBetween} against vanilla's walk ({@code tools/datagen}
 * {@code InsideBlockSamples}): same positions in the same order with the same iteration numbers, same result, also
 * when the visitor stops early. Far coordinates exercise the float clamps, long segments the iteration counts.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class InsideBlocksDifferentialTest {

    @Test
    void walksLikeVanilla() throws IOException {
        List<Sample> samples = read();
        assertTrue(samples.size() >= 1000);
        int longWalks = 0;
        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            List<int[]> visits = new ArrayList<>();
            int stopAfter = sample.stopAfter;
            InsideBlocks.Visitor visitor = (x, y, z, iteration) -> {
                visits.add(new int[]{x, y, z, iteration});
                return (stopAfter < 0 || visits.size() < stopAfter) && visits.size() < 4_000;
            };
            boolean result = InsideBlocks.forEachBlockIntersectedBetween(sample.from, sample.to, sample.box, visitor);
            int index = i;
            assertEquals(sample.visits.size(), visits.size(), () -> "sample " + index + " visit count " + sample);
            for (int v = 0; v < visits.size(); v++) {
                int[] expected = sample.visits.get(v);
                int[] actual = visits.get(v);
                int at = v;
                assertTrue(Arrays.equals(expected, actual),
                        () -> "sample " + index + " visit " + at + ": vanilla " + Arrays.toString(expected) + " ours " + Arrays.toString(actual));
            }
            assertEquals(sample.result, result, () -> "sample " + index + " result");
            if (visits.stream().anyMatch(v -> v[3] >= 16)) {
                longWalks++;
            }
        }
        assertTrue(longWalks > 50, "samples reach the 16-step budget of checkInsideBlocks: " + longWalks);
    }

    record Sample(Vec3d from, Vec3d to, Aabb box, int stopAfter, boolean result, List<int[]> visits) {
        @Override
        public String toString() {
            return "from " + from + " to " + to + " box " + box + " stopAfter " + stopAfter;
        }
    }

    private static List<Sample> read() throws IOException {
        String resource = "/vanilla/" + NativeRuntime.PROTOCOL + "/inside.bin.gz";
        try (InputStream raw = InsideBlocksDifferentialTest.class.getResourceAsStream(resource)) {
            if (raw == null) {
                throw new IOException("Missing " + resource);
            }
            DataInputStream in = new DataInputStream(new GZIPInputStream(raw, 1 << 16));
            if (in.readInt() != 0x4D414349 || in.readShort() != 1 || in.readInt() != NativeRuntime.PROTOCOL) {
                throw new IOException("Not the inside-walk samples of " + NativeRuntime.PROTOCOL);
            }
            int count = in.readInt();
            List<Sample> samples = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Vec3d from = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                Vec3d to = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                Aabb box = new Aabb(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble());
                int stopAfter = in.readInt();
                boolean result = in.readBoolean();
                int n = in.readInt();
                List<int[]> visits = new ArrayList<>(n);
                for (int v = 0; v < n; v++) {
                    visits.add(new int[]{in.readInt(), in.readInt(), in.readInt(), in.readInt()});
                }
                samples.add(new Sample(from, to, box, stopAfter, result, visits));
            }
            return samples;
        }
    }
}
