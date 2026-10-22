package ru.minecomplex.mac.listener;



/**
 * Reads a modern section update directly into compact deferred storage, as Grim's world reader did.
 * The section position is kept once; each record uses 12 local-position bits and 20 state-ID bits.
 * No EncodedBlock objects, world-coordinate vectors or retained network buffers are needed per record.
 */
public final class MultiBlockChangeBitRepackHandler {
    private MultiBlockChangeBitRepackHandler() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    public static Update read(net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket packet) {
        long[] entries = new long[packet.positions().length];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = (long) net.minecraft.world.level.block.Block.getId(packet.states()[i]) << 12
                    | Short.toUnsignedLong(packet.positions()[i]);
        }
        var section = packet.sectionPos();
        return new Update(section.x(), section.y(), section.z(), entries);
    }

    /** Immutable handler input shared by world hypotheses. All supported protocols use this wire layout. */
    public static final class Update {
        private final long sectionPosition;
        private final int[] packed;
        /** Unusual records wider than 32 bits retain their original representation instead of losing state bits. */
        private final long[] wide;


        /** From decoded records; copies their values and preserves their order, including duplicates. */
        public Update(int sectionX, int sectionY, int sectionZ, long[] entries) {
            sectionPosition = ((sectionX & 0x3fffffL) << 42) | ((sectionZ & 0x3fffffL) << 20) | (sectionY & 0xfffffL);
            int[] compact = new int[entries.length];
            long[] expanded = null;
            for (int i = 0; i < entries.length; i++) {
                long entry = entries[i];
                if (expanded == null && (entry >>> 32) != 0) expanded = expand(compact, i);
                if (expanded == null) compact[i] = (int) entry;
                else expanded[i] = entry;
            }
            packed = expanded == null ? compact : null;
            wide = expanded;
        }

        private static long[] expand(int[] compact, int through) {
            long[] expanded = new long[compact.length];
            for (int i = 0; i < through; i++) expanded[i] = Integer.toUnsignedLong(compact[i]);
            return expanded;
        }

        public int sectionX() { return (int) (sectionPosition >> 42); }
        public int sectionY() { return (int) (sectionPosition << 44 >> 44); }
        public int sectionZ() { return (int) (sectionPosition << 22 >> 42); }
        public int count() { return packed != null ? packed.length : wide.length; }
        public long entry(int index) { return packed != null ? Integer.toUnsignedLong(packed[index]) : wide[index]; }
        public int stateId(int index) { return (int) (entry(index) >>> 12); }
        public int blockX(int index) { return (sectionX() << 4) + (int) (entry(index) >>> 8 & 15); }
        public int blockY(int index) { return (sectionY() << 4) + (int) (entry(index) & 15); }
        public int blockZ(int index) { return (sectionZ() << 4) + (int) (entry(index) >>> 4 & 15); }

    }
}
