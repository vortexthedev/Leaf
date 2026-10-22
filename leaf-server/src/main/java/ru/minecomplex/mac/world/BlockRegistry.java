package ru.minecomplex.mac.world;
import ru.minecomplex.mac.util.Tri;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.packet.NativeRuntime;

/**
 * Block states in the client's own id space, generated from vanilla 26.3 code by {@code tools/datagen}
 * ({@code docs/vanilla-mechanics.md} §2). Immutable and shared by all sessions.
 */
public final class BlockRegistry {
    static final int MAGIC = 0x4D414342; // "MACB"
    static final int FORMAT = 4;

    public static final int FLAG_AIR = 1;
    public static final int FLAG_LARGE_COLLISION = 1 << 1;
    public static final int FLAG_FULL_BLOCK_COLLISION = 1 << 2;
    /** Collision shape differs by position (block offset). */
    public static final int FLAG_POSITION_DEPENDENT = 1 << 3;
    /** Collision shape depends on the colliding entity (liquids, scaffolding, powder snow …). */
    public static final int FLAG_CONTEXT_DEPENDENT = 1 << 4;
    public static final int FLAG_LIQUID = 1 << 5;
    public static final int FLAG_SOLID = 1 << 6;
    public static final int FLAG_OFFSET = 1 << 7;
    public static final int FLAG_FLUID_SOURCE = 1 << 8;
    public static final int FLAG_FLUID_FALLING = 1 << 9;
    /** The collision shape is vanilla's {@code Shapes.block()} instance ({@code BlockCollisions} fast path). */
    public static final int FLAG_BLOCK_SINGLETON = 1 << 10;
    /** Vanilla isSuffocating with no block entities and no tags. */
    public static final int FLAG_SUFFOCATING = 1 << 11;
    /** Default suffocation predicate, resolved from CAUSES_SUFFOCATION on capable clients. */
    public static final int FLAG_DEFAULT_SUFFOCATION = 1 << 12;
    /** Shulker suffocation requires the client block entity's closed state. */
    public static final int FLAG_BLOCK_ENTITY_SUFFOCATION = 1 << 13;

    private static volatile BlockRegistry loaded;

    private final String versionName;
    private final String[] fluids;
    private final BlockType[] blocks;
    private final Map<String, BlockType> blocksByName;
    private final VoxelShape[] shapes;
    private final String[] propertyStrings;
    private final short[] stateBlock;
    private final short[] stateProperties;
    private final short[] stateShape;
    private final short[] stateBaseShape;
    private final short[] stateFlags;
    private final byte[] stateFluid;
    private final byte[] stateFluidAmount;

    private BlockRegistry(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) {
            throw new IOException("Not a block registry");
        }
        int format = in.readShort();
        if (format != FORMAT) {
            throw new IOException("Unsupported block registry format " + format);
        }
        int fileProtocol = in.readInt();
        if (fileProtocol != NativeRuntime.PROTOCOL) {
            throw new IOException("Registry of protocol " + fileProtocol + " loaded for " + NativeRuntime.PROTOCOL);
        }
        versionName = in.readUTF();

        fluids = new String[in.readInt()];
        for (int i = 0; i < fluids.length; i++) {
            fluids[i] = in.readUTF();
        }

        int blockCount = in.readInt();
        String[] names = new String[blockCount];
        int[] registryIds = new int[blockCount];
        String[] classNames = new String[blockCount];
        String[] classChains = new String[blockCount];
        float[] bounce = new float[blockCount];
        float[] friction = new float[blockCount];
        float[] speed = new float[blockCount];
        float[] jump = new float[blockCount];
        byte[] offsetKinds = new byte[blockCount];
        float[] maxHorizontal = new float[blockCount];
        float[] maxVertical = new float[blockCount];
        for (int i = 0; i < blockCount; i++) {
            names[i] = in.readUTF();
            registryIds[i] = in.readInt();
            classNames[i] = in.readUTF();
            classChains[i] = in.readUTF();
            friction[i] = in.readFloat();
            speed[i] = in.readFloat();
            jump[i] = in.readFloat();
            bounce[i] = in.readFloat();
            offsetKinds[i] = in.readByte();
            maxHorizontal[i] = in.readFloat();
            maxVertical[i] = in.readFloat();
        }

        shapes = new VoxelShape[in.readInt()];
        for (int i = 0; i < shapes.length; i++) {
            shapes[i] = readShape(in);
        }

        propertyStrings = new String[in.readInt()];
        for (int i = 0; i < propertyStrings.length; i++) {
            propertyStrings[i] = in.readUTF();
        }

        int stateCount = in.readInt();
        stateBlock = new short[stateCount];
        stateProperties = new short[stateCount];
        stateShape = new short[stateCount];
        stateBaseShape = new short[stateCount];
        stateFlags = new short[stateCount];
        stateFluid = new byte[stateCount];
        stateFluidAmount = new byte[stateCount];
        int[] first = new int[blockCount];
        int[] count = new int[blockCount];
        java.util.Arrays.fill(first, -1);
        for (int id = 0; id < stateCount; id++) {
            stateBlock[id] = in.readShort();
            stateProperties[id] = in.readShort();
            stateShape[id] = in.readShort();
            stateBaseShape[id] = in.readShort();
            stateFlags[id] = in.readShort();
            stateFluid[id] = in.readByte();
            stateFluidAmount[id] = in.readByte();
            int block = Short.toUnsignedInt(stateBlock[id]);
            if (first[block] < 0) {
                first[block] = id;
            }
            count[block]++;
        }

        blocks = new BlockType[blockCount];
        blocksByName = new HashMap<>(blockCount * 2);
        for (int i = 0; i < blockCount; i++) {
            blocks[i] = new BlockType(i, names[i], registryIds[i], classNames[i], Set.of(classChains[i].split(">")),
                    friction[i], speed[i], jump[i], bounce[i], first[i], count[i],
                    BlockType.OffsetKind.values()[offsetKinds[i]], maxHorizontal[i], maxVertical[i]);
            blocksByName.put(names[i], blocks[i]);
        }
    }

    private static VoxelShape readShape(DataInputStream in) throws IOException {
        byte kind = in.readByte();
        double[] xs = readDoubles(in);
        double[] ys = readDoubles(in);
        double[] zs = readDoubles(in);
        int xSize = in.readInt();
        int ySize = in.readInt();
        int zSize = in.readInt();
        long[] bits = new long[in.readInt()];
        for (int i = 0; i < bits.length; i++) {
            bits[i] = in.readLong();
        }
        return switch (kind) {
            case 0 -> VoxelShape.EMPTY;
            case 1 -> VoxelShape.cube(xSize, ySize, zSize, bits);
            case 2 -> VoxelShape.array(xSize, ySize, zSize, bits, xs, ys, zs);
            default -> throw new IOException("Unknown shape kind " + kind);
        };
    }

    private static double[] readDoubles(DataInputStream in) throws IOException {
        double[] values = new double[in.readInt()];
        for (int i = 0; i < values.length; i++) {
            values[i] = in.readDouble();
        }
        return values;
    }

    /** The bundled registry ({@code mac/blocks/777.bin.gz}), loaded on first use. */
    public static BlockRegistry get() {
        BlockRegistry registry = loaded;
        if (registry != null) return registry;
        synchronized (BlockRegistry.class) {
            if (loaded == null) loaded = load();
            return loaded;
        }
    }

    private static BlockRegistry load() {
        String resource = "/mac/blocks/" + NativeRuntime.PROTOCOL + ".bin.gz";
        try (InputStream raw = BlockRegistry.class.getResourceAsStream(resource)) {
            if (raw == null) {
                throw new IllegalStateException("Missing resource " + resource);
            }
            return new BlockRegistry(new DataInputStream(new GZIPInputStream(raw, 1 << 16)));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load " + resource, e);
        }
    }

    /** Release the data was generated from. */
    public String versionName() {
        return versionName;
    }

    public int stateCount() {
        return stateBlock.length;
    }

    public boolean isValidState(int stateId) {
        return stateId >= 0 && stateId < stateBlock.length;
    }

    public BlockType  block(int stateId) {
        return blocks[Short.toUnsignedInt(stateBlock[stateId])];
    }

    public BlockType block(String name) {
        return blocksByName.get(name);
    }

    public List<BlockType> blocks() {
        return List.of(blocks);
    }

    /** Property values as vanilla prints them, e.g. {@code facing=north,half=top}; empty for blocks without any. */
    public String properties(int stateId) {
        return propertyStrings[Short.toUnsignedInt(stateProperties[stateId])];
    }

    /** State id of {@code block} with exactly these properties, or {@code -1}. */
    public int stateId(String block, String properties) {
        BlockType type = blocksByName.get(block);
        if (type == null) {
            return -1;
        }
        for (int id = type.firstStateId(); id < type.firstStateId() + type.stateCount(); id++) {
            if (properties(id).equals(properties)) {
                return id;
            }
        }
        return -1;
    }

    /**
     * Collision shape at the origin in an empty world with an empty collision context; {@link VoxelShape#BLOCK} itself
     * for {@link #FLAG_BLOCK_SINGLETON} states. For states with
     * {@link #FLAG_POSITION_DEPENDENT} or {@link #FLAG_CONTEXT_DEPENDENT} this is only the base the dynamic
     * behaviour starts from.
     */
    public VoxelShape collisionShape(int stateId) {
        // vanilla's Shapes.block() singleton is observable (BlockCollisions, inside-block checks compare identity)
        return has(stateId, FLAG_BLOCK_SINGLETON) ? VoxelShape.BLOCK : shapes[Short.toUnsignedInt(stateShape[stateId])];
    }

    /**
     * For {@link #FLAG_POSITION_DEPENDENT} states the unmoved shape that vanilla moves by {@code getOffset(pos)};
     * otherwise the same as {@link #collisionShape}.
     */
    public VoxelShape baseCollisionShape(int stateId) {
        return shapes[Short.toUnsignedInt(stateBaseShape[stateId])];
    }

    public int flags(int stateId) {
        return Short.toUnsignedInt(stateFlags[stateId]);
    }

    public boolean has(int stateId, int flag) {
        return (flags(stateId) & flag) != 0;
    }

    public boolean isAir(int stateId) {
        return has(stateId, FLAG_AIR);
    }

    /** Unresolved shulker block entities are explicit uncertainty, never a static registry approximation. */
    public ru.minecomplex.mac.util.Tri suffocation(int stateId, BlockTags tags) {
        if (has(stateId, FLAG_BLOCK_ENTITY_SUFFOCATION)) {
            return ru.minecomplex.mac.util.Tri.EITHER;
        }
        boolean result;
        if (has(stateId, FLAG_DEFAULT_SUFFOCATION)
                && true) {
            result = tags.contains(BlockTag.CAUSES_SUFFOCATION, block(stateId))
                    && has(stateId, FLAG_FULL_BLOCK_COLLISION);
        } else {
            result = has(stateId, FLAG_SUFFOCATING);
        }
        return result ? ru.minecomplex.mac.util.Tri.TRUE : ru.minecomplex.mac.util.Tri.FALSE;
    }

    /** Fluid registry key of the state's fluid ({@code minecraft:empty} when none). */
    public String fluid(int stateId) {
        return fluids[stateFluid[stateId]];
    }

    public int fluidAmount(int stateId) {
        return stateFluidAmount[stateId];
    }

    /** Number of distinct collision shapes (diagnostics). */
    public int shapeCount() {
        return shapes.length;
    }

    public List<String> fluids() {
        return new ArrayList<>(List.of(fluids));
    }
}
