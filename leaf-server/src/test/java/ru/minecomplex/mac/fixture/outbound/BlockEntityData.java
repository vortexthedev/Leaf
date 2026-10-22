package ru.minecomplex.mac.fixture.outbound;

import java.util.Arrays;
import java.util.Objects;

/** Typed block-entity update; NBT is an unnamed binary root, including its tag type. END represents null. */
public record BlockEntityData(int x, int y, int z, String type, byte[] nbt) implements OutboundEvent {
    public BlockEntityData {
        Objects.requireNonNull(type);
        nbt = nbt.clone();
    }

    @Override public byte[] nbt() { return nbt.clone(); }

    @Override public boolean equals(Object other) {
        return other instanceof BlockEntityData that && x == that.x && y == that.y && z == that.z
                && type.equals(that.type) && Arrays.equals(nbt, that.nbt);
    }

    @Override public int hashCode() { return 31 * Objects.hash(x, y, z, type) + Arrays.hashCode(nbt); }

    @Override public String toString() { return "BlockEntityData[" + x + ", " + y + ", " + z + ", " + type + ", " + nbt.length + " bytes]"; }
}
