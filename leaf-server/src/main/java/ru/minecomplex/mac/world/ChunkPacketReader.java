package ru.minecomplex.mac.world;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

/** Reads native chunk payloads into the copy-on-write world representation. */
public final class ChunkPacketReader {
    private ChunkPacketReader() { }

    public static List<PalettedSection> sections(ClientboundLevelChunkWithLightPacket packet, RegistryAccess registries) {
        int globalBits = 32 - Integer.numberOfLeadingZeros(Block.BLOCK_STATE_REGISTRY.size() - 1);
        var sections = new ArrayList<PalettedSection>();
        var factory = PalettedContainerFactory.create(registries);
        var bytes = packet.chunkData().getReadBuffer();
        try {
            while (bytes.isReadable()) {
                var nativeSection = new LevelChunkSection(factory);
                nativeSection.read(bytes);
                var section = PalettedSection.single(0, globalBits);
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    int state = Block.getId(nativeSection.getBlockState(x, y, z));
                    if (state != 0) section.set(x, y, z, state);
                }
                sections.add(section);
            }
        } finally { bytes.release(); }
        return List.copyOf(sections);
    }

    public static List<BlockEntityData> blockEntities(ClientboundLevelChunkWithLightPacket packet) {
        var entities = new ArrayList<BlockEntityData>();
        packet.chunkData().forEachBlockEntityTag(packet.x(), packet.z(), (pos, type, tag) ->
                entities.add(new BlockEntityData(pos.getX(), pos.getY(), pos.getZ(),
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).toString(), BlockEntityData.nbt(tag))));
        return List.copyOf(entities);
    }
}
