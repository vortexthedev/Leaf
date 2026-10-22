package ru.minecomplex.mac.bukkit.debug;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import ru.minecomplex.mac.world.BlockTag;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.world.ResolvedBlockTags;

/**
 * Diagnostic fallback when the connection's actual block {@code update_tags} was not captured. Reads the server's
 * registry once on the main thread and maps block keys to the client registry; actual configuration/play packets
 * take precedence in the world timeline, including later datapack updates.
 */
public final class ServerBlockTags {
    private final Map<BlockTag, List<String>> keys = new EnumMap<>(BlockTag.class);
    private volatile BlockTags resolved;

    /** Main thread. */
    public ServerBlockTags() {
        for (BlockTag tag : BlockTag.values()) {
            Tag<Material> server = Bukkit.getTag(Tag.REGISTRY_BLOCKS, NamespacedKey.fromString(tag.key()), Material.class);
            List<String> members = new ArrayList<>();
            if (server != null) {
                for (Material material : server.getValues()) members.add(material.getKey().toString());
            }
            keys.put(tag, List.copyOf(members));
        }
    }

    /** The tags in the client registry, resolved on first use. */
    public BlockTags tags() {
        BlockTags tags = resolved;
        if (tags == null) resolved = tags = ResolvedBlockTags.ofKeys(keys);
        return tags;
    }

    /** The members of each tag by block key, as the server lists them (recorded next to a session recording). */
    public Map<BlockTag, List<String>> keys() {
        return keys;
    }

}
