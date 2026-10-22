package ru.minecomplex.mac.world;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;

/**
 * Typed block-entity data, of a {@code block_entity_data} or a chunk's block entity; NBT is an unnamed binary root,
 * including its tag type. END represents null. The bytes are copied when the packet is handled: native NBT is
 * mutable and is not retained.
 */
public record BlockEntityData(int x, int y, int z, String type, byte[] nbt) implements BlockInput {
    public BlockEntityData {
        Objects.requireNonNull(type);
        nbt = nbt.clone();
    }

    public static BlockEntityData of(ClientboundBlockEntityDataPacket packet) {
        var pos = packet.getPos();
        return new BlockEntityData(pos.getX(), pos.getY(), pos.getZ(), net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(packet.getType()).toString(), nbt(packet.getTag()));
    }

    /** Typed NBT bytes of a tag ({@code null}: an end tag). */
    public static byte[] nbt(CompoundTag tag) {
        if (tag == null) return new byte[]{0};
        try {
            var bytes = new ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.writeAnyTag(tag, new DataOutputStream(bytes));
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    public byte[] nbt() {
        return nbt.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BlockEntityData that && x == that.x && y == that.y && z == that.z
                && type.equals(that.type) && Arrays.equals(nbt, that.nbt);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(x, y, z, type) + Arrays.hashCode(nbt);
    }

    @Override
    public String toString() {
        return "BlockEntityData[" + x + ", " + y + ", " + z + ", " + type + ", " + nbt.length + " bytes]";
    }
}
