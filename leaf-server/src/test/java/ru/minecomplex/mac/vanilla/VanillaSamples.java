package ru.minecomplex.mac.vanilla;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.Axis;

/**
 * Vanilla results recorded by {@code tools/datagen} ({@code samples.bin.gz}). The file holds, in
 * order: {@code Shapes.collide} samples, {@code getOffset} samples and (second gzip member) {@code BlockCollisions}
 * samples.
 */
public final class VanillaSamples {
    private static VanillaSamples loaded;

    public record Collide(int[] states, int[][] offsets, long baseX, long baseY, long baseZ, Aabb box, Axis axis,
                          double distance, double result) {
    }

    public record Offset(int state, int x, int y, int z, double dx, double dy, double dz) {
    }

    public record Iteration(int x0, int y0, int z0, int[] states, Aabb box, List<int[]> positions) {
        public static final int SIZE = 4;

        /** State at a world position of the sample's region, air elsewhere (as the vanilla proxy world). */
        public int stateAt(int x, int y, int z) {
            int dx = x - x0;
            int dy = y - y0;
            int dz = z - z0;
            if (dx < 0 || dy < 0 || dz < 0 || dx >= SIZE || dy >= SIZE || dz >= SIZE) {
                return 0;
            }
            return states[(dx * SIZE + dy) * SIZE + dz];
        }
    }

    public final List<Collide> collide = new ArrayList<>();
    public final List<Offset> offsets = new ArrayList<>();
    public final List<Iteration> iterations = new ArrayList<>();

    public static synchronized VanillaSamples get() {
        if (loaded == null) {
            try {
                loaded = new VanillaSamples();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return loaded;
    }

    private VanillaSamples() throws IOException {
        InputStream raw = VanillaSamples.class.getResourceAsStream("/vanilla/" + ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL + "/samples.bin.gz");
        if (raw == null) {
            throw new IOException("Missing vanilla samples");
        }
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(raw))) {
            if (in.readInt() != 0x4D414353 || in.readShort() != 2 || in.readInt() != ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL) {
                throw new IOException("Unexpected samples header");
            }
            in.readUTF();
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int shapes = in.readByte();
                int[] states = new int[shapes];
                int[][] offsets = new int[shapes][3];
                for (int s = 0; s < shapes; s++) {
                    states[s] = in.readInt();
                    offsets[s][0] = in.readByte();
                    offsets[s][1] = in.readByte();
                    offsets[s][2] = in.readByte();
                }
                long baseX = in.readLong();
                long baseY = in.readLong();
                long baseZ = in.readLong();
                Aabb box = new Aabb(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble());
                Axis axis = Axis.values()[in.readByte()];
                collide.add(new Collide(states, offsets, baseX, baseY, baseZ, box, axis, in.readDouble(), in.readDouble()));
            }
            count = in.readInt();
            for (int i = 0; i < count; i++) {
                offsets.add(new Offset(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readDouble(), in.readDouble(), in.readDouble()));
            }
            count = in.readInt();
            for (int i = 0; i < count; i++) {
                int x0 = in.readInt();
                int y0 = in.readInt();
                int z0 = in.readInt();
                int[] states = new int[Iteration.SIZE * Iteration.SIZE * Iteration.SIZE];
                for (int s = 0; s < states.length; s++) {
                    states[s] = in.readInt();
                }
                Aabb box = new Aabb(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble());
                int n = in.readInt();
                List<int[]> positions = new ArrayList<>(n);
                for (int p = 0; p < n; p++) {
                    positions.add(new int[]{in.readInt(), in.readInt(), in.readInt()});
                }
                iterations.add(new Iteration(x0, y0, z0, states, box, positions));
            }
        }
    }
}
