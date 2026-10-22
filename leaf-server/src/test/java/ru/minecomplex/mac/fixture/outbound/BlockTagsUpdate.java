package ru.minecomplex.mac.fixture.outbound;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import ru.minecomplex.mac.world.BlockTag;
import ru.minecomplex.mac.world.ResolvedBlockTags;

/**
 * The block registry's tags of an {@code update_tags} (configuration or play; {@code ClientCommonPacketListenerImpl
 * .handleUpdateTags} replaces every tag of the registries it lists): the client's block registry ids, as the client
 * received them (after Via). {@code keys}: the tag keys separated by spaces; {@code members}: for each tag in that order,
 * its member count, then its ids.
 */
public record BlockTagsUpdate(String keys, int[] members) implements OutboundEvent {

    public BlockTagsUpdate {
        members = members.clone();
    }

    /** From tag key → member ids, in iteration order. */
    public static BlockTagsUpdate of(Map<String, int[]> tags) {
        StringBuilder keys = new StringBuilder();
        int size = 0;
        for (int[] ids : tags.values()) size += 1 + ids.length;
        int[] members = new int[size];
        int at = 0;
        for (var tag : tags.entrySet()) {
            if (!keys.isEmpty()) keys.append(' ');
            keys.append(tag.getKey());
            members[at++] = tag.getValue().length;
            System.arraycopy(tag.getValue(), 0, members, at, tag.getValue().length);
            at += tag.getValue().length;
        }
        return new BlockTagsUpdate(keys.toString(), members);
    }

    /** Tag key → member ids. */
    public Map<String, int[]> tags() {
        Map<String, int[]> tags = new LinkedHashMap<>();
        if (keys.isEmpty()) return tags;
        int at = 0;
        for (String key : keys.split(" ")) {
            int count = members[at++];
            tags.put(key, Arrays.copyOfRange(members, at, at + count));
            at += count;
        }
        return tags;
    }

    /** The modelled tags ({@link BlockTag}); a tag the update does not list has no members (the client's tag is empty). */
    public ResolvedBlockTags resolve() {
        Map<String, int[]> tags = tags();
        Map<BlockTag, int[]> ids = new EnumMap<>(BlockTag.class);
        for (BlockTag tag : BlockTag.values()) ids.put(tag, tags.getOrDefault(tag.key(), new int[0]));
        return new ResolvedBlockTags(ids);
    }

    @Override public int[] members() { return members.clone(); }

    @Override
    public boolean equals(Object other) {
        return other instanceof BlockTagsUpdate that && keys.equals(that.keys) && Arrays.equals(members, that.members);
    }

    @Override
    public int hashCode() {
        return 31 * keys.hashCode() + Arrays.hashCode(members);
    }

    @Override
    public String toString() {
        return "BlockTagsUpdate[" + (keys.isEmpty() ? 0 : keys.split(" ").length) + " tags]";
    }
}
