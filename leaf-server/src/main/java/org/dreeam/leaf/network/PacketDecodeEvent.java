package org.dreeam.leaf.network;

import net.minecraft.nbt.Tag;
import net.minecraft.network.ConnectionProtocol;
import org.jspecify.annotations.Nullable;

/**
 * Borrowed decoded structure. SLOT_COUNT runs before map allocation; NBT runs after native parsing.
 */
public final class PacketDecodeEvent {
    private final PacketUser user;
    private final ConnectionProtocol protocol;
    private final Kind kind;
    private final int count;
    private final @Nullable Tag tag;
    private final java.util.Map<net.minecraft.nbt.ListTag, java.util.List<Tag>> rawLists;
    private final java.util.Map<net.minecraft.nbt.ListTag, Byte> rawListTypes;
    boolean readOnly;
    private boolean cancelled;
    PacketDecodeEvent(PacketUser user, ConnectionProtocol protocol, Kind kind, int count, @Nullable Tag tag, java.util.Map<net.minecraft.nbt.ListTag, java.util.List<Tag>> rawLists, java.util.Map<net.minecraft.nbt.ListTag, Byte> rawListTypes) {
        this.user = user;
        this.protocol = protocol;
        this.kind = kind;
        this.count = count;
        this.tag = tag;
        this.rawLists = rawLists;
        this.rawListTypes = rawListTypes;
    }

    public PacketUser getUser() {
        return user;
    }

    public ConnectionProtocol getProtocol() {
        return protocol;
    }

    public Kind getKind() {
        return kind;
    }

    public int getCount() {
        return count;
    }

    public @Nullable Tag getTag() {
        return tag;
    }

    public Iterable<Tag> getRawListChildren(net.minecraft.nbt.ListTag list) {
        var raw = rawLists.get(list);
        return raw == null ? list : java.util.Collections.unmodifiableList(raw);
    }

    public int getRawListElementType(net.minecraft.nbt.ListTag list) {
        var type = rawListTypes.get(list);
        return type == null ? (list.isEmpty() ? 0 : list.get(0).getId()) : Byte.toUnsignedInt(type);
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean value) {
        if (readOnly) throw new IllegalStateException("MONITOR decode handlers are read-only");
        cancelled = value;
    }

    public enum Kind {SLOT_COUNT, NBT}
}
