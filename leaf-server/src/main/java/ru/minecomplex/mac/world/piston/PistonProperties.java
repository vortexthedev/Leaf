package ru.minecomplex.mac.world.piston;

import ru.minecomplex.mac.packet.NativeRuntime;
import ru.minecomplex.mac.world.BlockRegistry;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * Intrinsic vanilla pushability, block-entity presence and real default states in the client registry.
 */
public final class PistonProperties {
    private static volatile PistonProperties loaded;
    private final BlockRegistry registry;
    private final byte[] pushable;
    private final boolean[] blockEntity;
    private final int[] defaults;
    private final Reaction[] reactions;
    private final byte[] neighbourFlags;
    private PistonProperties() {
        registry = BlockRegistry.get();
        String path = "/mac/pistons/" + NativeRuntime.PROTOCOL + ".bin.gz";
        try (var raw = PistonProperties.class.getResourceAsStream(path)) {
            if (raw == null) throw new IllegalStateException("Missing " + path);
            try (var in = new DataInputStream(new GZIPInputStream(raw))) {
                if (in.readInt() != 0x4d415050 || in.readUnsignedShort() != 2 || in.readInt() != NativeRuntime.PROTOCOL)
                    throw new IOException("Invalid piston properties " + path);
                int size = in.readInt();
                if (size != registry.stateCount()) throw new IOException("Piston registry size differs");
                pushable = new byte[size];
                blockEntity = new boolean[size];
                defaults = new int[size];
                reactions = new Reaction[size];
                neighbourFlags = new byte[size];
                for (int i = 0; i < size; i++) {
                    pushable[i] = in.readByte();
                    blockEntity[i] = in.readBoolean();
                    defaults[i] = in.readInt();
                    reactions[i] = Reaction.valueOf(in.readUTF());
                    neighbourFlags[i] = in.readByte();
                }
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    public static PistonProperties get() {
        PistonProperties properties = loaded;
        if (properties != null) return properties;
        synchronized (PistonProperties.class) {
            if (loaded == null) loaded = new PistonProperties();
            return loaded;
        }
    }

    public boolean hasBlockEntity(int state) {
        return blockEntity[state];
    }

    public int defaultState(int state) {
        return defaults[state];
    }

    public int defaultState(String name) {
        return defaults[registry.block(name).firstStateId()];
    }

    public Reaction reaction(int state) {
        return reactions[state];
    }

    public boolean baseNeighbourMethod(int state, int methodBit) {
        return (neighbourFlags[state] & methodBit) != 0;
    }

    public boolean intrinsicallyPushable(int state, boolean destroy, boolean aligned) {
        return (pushable[state] & (1 << ((destroy ? 2 : 0) + (aligned ? 1 : 0)))) != 0;
    }

    public int with(int state, String property, String value) {
        var properties = new TreeMap<String, String>();
        String text = registry.properties(state);
        if (!text.isEmpty()) for (String part : text.split(",")) {
            String[] pair = part.split("=", 2);
            properties.put(pair[0], pair[1]);
        }
        if (!properties.containsKey(property)) throw new IllegalArgumentException("Missing property " + property);
        properties.put(property, value);
        String joined = String.join(",", properties.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
        int id = registry.stateId(registry.block(state).name(), joined);
        if (id < 0) throw new IllegalArgumentException("Invalid property " + property + "=" + value);
        return id;
    }

    public String value(int state, String property) {
        for (String part : registry.properties(state).split(","))
            if (part.startsWith(property + "=")) return part.substring(property.length() + 1);
        return null;
    }

    public enum Reaction {NORMAL, DESTROY, BLOCK, IGNORE, PUSH_ONLY}
}
