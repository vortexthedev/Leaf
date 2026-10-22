package ru.minecomplex.mac.world.piston;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.world.BlockDirection;
import ru.minecomplex.mac.world.BlockPosition;
import ru.minecomplex.mac.world.BlockRegistry;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class PistonResolverOracleTest {

    static BlockPosition pos(DataInputStream in) throws IOException { return new BlockPosition(in.readInt(), in.readInt(), in.readInt()); }
    static List<BlockPosition> list(DataInputStream in) throws IOException {
        var result = new ArrayList<BlockPosition>(); for (int n = in.readInt(); n > 0; n--) result.add(pos(in)); return result;
    }
    @Test
    void matchesVanilla() throws Exception {
        var raw = getClass().getResourceAsStream("/vanilla/777/piston-resolver.bin.gz"); assertNotNull(raw);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d415052, in.readInt()); assertEquals(1, in.readUnsignedShort()); assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            assertEquals(2048, in.readInt());
            for (int s = 0; s < 2048; s++) {
                var origin = pos(in); var direction = BlockDirection.values()[in.readInt()]; boolean extend = in.readBoolean(); double size = in.readDouble();
                var blocks = new HashMap<BlockPosition, Integer>();
                for (int n = in.readInt(); n > 0; n--) blocks.put(pos(in), in.readInt());
                var world = new PistonResolver.World() {
                    public int block(BlockPosition p) { return blocks.getOrDefault(p, 0); }
                    public int minY() { return -64; }
                    public int maxY() { return 319; }
                    public boolean withinBorder(BlockPosition p) {
                        double min = Math.max(-size / 2.0, -29999984), max = Math.min(size / 2.0, 29999984);
                        return p.x() >= min && p.x() < max && p.z() >= min && p.z() < max;
                    }
                };
                var resolver = new PistonResolver(world, BlockRegistry.get(), origin, direction, extend);
                String context = "26.3 case " + s;
                assertEquals(in.readBoolean(), resolver.resolve(), context);
                assertEquals(list(in), resolver.toPush(), context + " push order");
                assertEquals(list(in), resolver.toDestroy(), context + " destroy order");
                int state = in.readInt(); var move = BlockDirection.values()[in.readInt()]; var connection = BlockDirection.values()[in.readInt()];
                boolean destroy = in.readBoolean();
                assertEquals(in.readBoolean(), resolver.isPushable(state, origin, move, destroy, connection), context + " pushable");
            }
            assertEquals(-1, in.read());
        }

    }
}
