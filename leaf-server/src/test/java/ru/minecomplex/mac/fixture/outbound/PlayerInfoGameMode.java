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
 * {@code player_info_update} as far as the model reads it: the client's own entry, and the profiles it adds.
 *
 * <p>The local player's {@code isSpectator()} reads the game mode of its own {@code PlayerInfo}, not the game mode of
 * {@code game_event}. {@code ClientPacketListener.handlePlayerInfoUpdate} first creates a {@code PlayerInfo} (game mode
 * survival) for {@code ADD_PLAYER} entries without one ({@code putIfAbsent}: an existing entry is kept), then applies the
 * actions to entries that exist; an update for a missing entry is ignored. A player entity is created only for a UUID
 * with a {@code PlayerInfo}, with its profile, whose name names it on the scoreboard.
 *
 * @param added           the packet carried {@code ADD_PLAYER} for the own profile
 * @param updatesGameMode the packet carried {@code UPDATE_GAME_MODE} for the own profile
 * @param gameMode        wire id ({@code GameType.byId}: ids out of range are survival); meaningful with {@code updatesGameMode}
 * @param addedProfiles   the {@code ADD_PLAYER} entries' profiles ({@link #profiles}), empty for none
 * @param gameModes       every entry's {@code UPDATE_GAME_MODE} ({@link #gameModes()}: a remote player's
 *                        {@code isSpectator()}), empty for none
 */
public record PlayerInfoGameMode(boolean added, boolean updatesGameMode, int gameMode, byte[] addedProfiles, byte[] gameModesPayload)
        implements OutboundEvent {

    /** One added profile. */
    public record Profile(UUID id, String name) {
    }

    /** One entry's game mode ({@code GameType} wire id). */
    public record EntryGameMode(UUID id, int gameMode) {
    }

    public PlayerInfoGameMode(boolean added, boolean updatesGameMode, int gameMode) {
        this(added, updatesGameMode, gameMode, new byte[0], new byte[0]);
    }

    public PlayerInfoGameMode(boolean added, boolean updatesGameMode, int gameMode, byte[] addedProfiles) {
        this(added, updatesGameMode, gameMode, addedProfiles, new byte[0]);
    }

    public PlayerInfoGameMode {
        addedProfiles = addedProfiles == null ? new byte[0] : addedProfiles.clone();
        gameModesPayload = gameModesPayload == null ? new byte[0] : gameModesPayload.clone();
    }

    @Override
    public byte[] gameModesPayload() {
        return gameModesPayload.clone();
    }

    public List<EntryGameMode> gameModes() {
        if (gameModesPayload.length == 0) return List.of();
        try (var in = new DataInputStream(new ByteArrayInputStream(gameModesPayload))) {
            int count = in.readInt();
            var modes = new ArrayList<EntryGameMode>(count);
            for (int i = 0; i < count; i++) modes.add(new EntryGameMode(new UUID(in.readLong(), in.readLong()), in.readInt()));
            return List.copyOf(modes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] encodeGameModes(List<EntryGameMode> modes) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(modes.size());
            for (EntryGameMode mode : modes) {
                out.writeLong(mode.id().getMostSignificantBits());
                out.writeLong(mode.id().getLeastSignificantBits());
                out.writeInt(mode.gameMode());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    @Override
    public byte[] addedProfiles() {
        return addedProfiles.clone();
    }

    /** Whether the packet concerned the own entry (a player change). */
    public boolean concernsOwnEntry() {
        return added || updatesGameMode;
    }

    public List<Profile> profiles() {
        if (addedProfiles.length == 0) return List.of();
        try (var in = new DataInputStream(new ByteArrayInputStream(addedProfiles))) {
            int count = in.readInt();
            var profiles = new ArrayList<Profile>(count);
            for (int i = 0; i < count; i++) profiles.add(new Profile(new UUID(in.readLong(), in.readLong()), in.readUTF()));
            return List.copyOf(profiles);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] encode(List<Profile> profiles) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(profiles.size());
            for (Profile profile : profiles) {
                out.writeLong(profile.id().getMostSignificantBits());
                out.writeLong(profile.id().getLeastSignificantBits());
                out.writeUTF(profile.name());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PlayerInfoGameMode that && added == that.added && updatesGameMode == that.updatesGameMode
                && gameMode == that.gameMode && profiles().equals(that.profiles()) && gameModes().equals(that.gameModes());
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(added, updatesGameMode, gameMode, profiles(), gameModes());
    }

    @Override
    public String toString() {
        return "PlayerInfoGameMode[added=" + added + ", updatesGameMode=" + updatesGameMode + ", gameMode=" + gameMode
                + ", profiles=" + profiles() + ", gameModes=" + gameModes() + "]";
    }
}
