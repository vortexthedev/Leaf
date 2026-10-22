package ru.minecomplex.mac.fixture.outbound;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code player_info_remove}. Listing the client's own profile: the local player keeps the {@code PlayerInfo} it cached
 * ({@code AbstractClientPlayer.getPlayerInfo}), so later entries for the same profile are not the one it reads. Other
 * profiles lose their {@code PlayerInfo}: a player entity added afterwards for them is not created (one present keeps
 * its profile).
 *
 * @param othersOnly the packet did not list the own profile
 * @param profileIds the removed profiles ({@link #ids}), empty when not recorded
 */
public record PlayerInfoRemove(boolean othersOnly, byte[] profileIds) implements OutboundEvent {

    /** A removal of the own profile (the others not recorded). */
    public PlayerInfoRemove() {
        this(false, new byte[0]);
    }

    public PlayerInfoRemove {
        profileIds = profileIds == null ? new byte[0] : profileIds.clone();
    }

    @Override
    public byte[] profileIds() {
        return profileIds.clone();
    }

    public List<UUID> ids() {
        if (profileIds.length == 0) return List.of();
        try (var in = new DataInputStream(new ByteArrayInputStream(profileIds))) {
            int count = in.readInt();
            var ids = new ArrayList<UUID>(count);
            for (int i = 0; i < count; i++) ids.add(new UUID(in.readLong(), in.readLong()));
            return List.copyOf(ids);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] encode(List<UUID> ids) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(ids.size());
            for (UUID id : ids) {
                out.writeLong(id.getMostSignificantBits());
                out.writeLong(id.getLeastSignificantBits());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PlayerInfoRemove that && othersOnly == that.othersOnly && ids().equals(that.ids());
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(othersOnly, ids());
    }

    @Override
    public String toString() {
        return "PlayerInfoRemove[othersOnly=" + othersOnly + ", ids=" + ids() + "]";
    }
}
