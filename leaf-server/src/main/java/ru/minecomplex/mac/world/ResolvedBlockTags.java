package ru.minecomplex.mac.world;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import java.util.BitSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;

/** Immutable resolved update_tags data in the client's block registry IDs, suitable for hypothesis forks. */
public final class ResolvedBlockTags implements BlockTags {
    private static final net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<net.minecraft.world.level.block.Block>> BLOCK = net.minecraft.core.registries.Registries.BLOCK;
    private static final Map<String, BlockTag> TRACKED = java.util.Arrays.stream(BlockTag.values())
            .collect(java.util.stream.Collectors.toUnmodifiableMap(BlockTag::key, tag -> tag));
    private final Map<BlockTag,BitSet> members;
    public ResolvedBlockTags(Map<BlockTag,int[]> registryIds) {
        var result=new EnumMap<BlockTag,BitSet>(BlockTag.class);
        registryIds.forEach((tag,ids)->{
            var bits=new BitSet();
            for(int id:ids) {if(id<0)throw new IllegalArgumentException("Negative client block registry ID");bits.set(id);}
            result.put(tag,bits);
        });
        members=Map.copyOf(result);
    }
    public static boolean hasBlockRegistry(ClientboundUpdateTagsPacket packet) {
        return packet.tags().containsKey(BLOCK);
    }

    /** A packet replaces only the registries it names; item/fluid-only updates leave block tags unchanged. */
    public static BlockTags apply(BlockTags previous, ClientboundUpdateTagsPacket packet) {
        return hasBlockRegistry(packet) ? of(packet) : previous;
    }

    /**
     * The block registry's tags of an {@code update_tags} (the configuration phase's and the play phase's share the
     * format): the client's block registry ids, as written to it; tags the model does not read are dropped.
     */
    public static ResolvedBlockTags of(ClientboundUpdateTagsPacket packet) {
        Map<BlockTag, int[]> tags = new EnumMap<>(BlockTag.class);
        var blockTags = packet.tags().get(BLOCK);
        if (blockTags != null) blockTags.tags().forEach((key, values) -> {
            BlockTag tracked = TRACKED.get(key.toString());
            if (tracked != null) tags.put(tracked, values.toIntArray());
        });
        return new ResolvedBlockTags(tags);
    }

    /** The modelled tags of a key → registry ids map (absent ones are empty). */
    public static ResolvedBlockTags of(Map<String, int[]> tags) {
        Map<BlockTag, int[]> ids = new java.util.EnumMap<>(BlockTag.class);
        for (BlockTag tag : BlockTag.values()) ids.put(tag, tags.getOrDefault(tag.key(), new int[0]));
        return new ResolvedBlockTags(ids);
    }

    /** Maps fallback block keys to the client registry; blocks absent from it are omitted. */
    public static ResolvedBlockTags ofKeys(Map<BlockTag, List<String>> keys) {
        BlockRegistry registry = BlockRegistry.get();
        Map<BlockTag, int[]> ids = new EnumMap<>(BlockTag.class);
        keys.forEach((tag, members) -> ids.put(tag, members.stream().map(registry::block)
                .filter(Objects::nonNull).mapToInt(BlockType::registryId).toArray()));
        return new ResolvedBlockTags(ids);
    }
    @Override public boolean contains(BlockTag tag,BlockType block) {
        BitSet ids=members.get(tag);return ids!=null&&ids.get(block.registryId());
    }
}
