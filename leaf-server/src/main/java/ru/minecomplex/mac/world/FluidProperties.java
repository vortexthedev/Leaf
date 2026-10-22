package ru.minecomplex.mac.world;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.entity.Pose;
import ru.minecomplex.mac.packet.NativeRuntime;

/**
 * Cached vanilla facts indexed by block state: own fluid height, support faces, motion blocking and ice,
 * plus the player's pose eye heights. Loaded once, not parsed for individual fluid contacts.
 * Support faces are not collision shapes.
 */
public final class FluidProperties {
    private static volatile FluidProperties loaded;
    private final float[] heights, eyes = new float[Pose.PLAYER_POSES.size()];
    private final byte[] faces;
    private final boolean[] ice, motion;

    private FluidProperties() throws IOException {
        String path = "/mac/fluids/" + NativeRuntime.PROTOCOL + ".bin.gz";
        var raw = FluidProperties.class.getResourceAsStream(path);
        if (raw == null) throw new IOException("Missing " + path);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            if (in.readInt() != 0x4d414650 || in.readUnsignedShort() != 1 || in.readInt() != NativeRuntime.PROTOCOL) {
                throw new IOException("Invalid fluid properties");
            }
            for (int i = 0; i < eyes.length; i++) eyes[i] = in.readFloat();
            int n = in.readInt();
            if (n != BlockRegistry.get().stateCount()) throw new IOException("Fluid registry mismatch");
            heights = new float[n]; faces = new byte[n]; ice = new boolean[n]; motion = new boolean[n];
            for (int i = 0; i < n; i++) {
                heights[i] = in.readFloat(); faces[i] = in.readByte();
                motion[i] = in.readBoolean(); ice[i] = in.readBoolean();
            }
            if (in.read() != -1) throw new IOException("Trailing fluid properties");
        }
    }

    public static FluidProperties get() {
        FluidProperties properties = loaded;
        if (properties != null) return properties;
        synchronized (FluidProperties.class) {
            if (loaded == null) loaded = load();
            return loaded;
        }
    }

    private static FluidProperties load() {
        try { return new FluidProperties(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public float ownHeight(int state) { return heights[state]; }
    /** Direction indices NORTH, EAST, SOUTH, WEST, DOWN, matching the generator. */
    public boolean sturdy(int state, int direction) { return (faces[state] & (1 << direction)) != 0; }
    public boolean blocksMotion(int state) { return motion[state]; }
    public boolean ice(int state) { return ice[state]; }
    public float eyeHeight(Pose pose, float scale) {
        Pose dimensions = pose.dimensionPose();
        return dimensions == Pose.SLEEPING || dimensions == Pose.DYING ? eyes[dimensions.ordinal()] : eyes[dimensions.ordinal()] * scale;
    }
}
