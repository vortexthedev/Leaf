package ru.minecomplex.mac.fixture.outbound;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code set_player_team} as far as entity pushing reads it ({@code ClientPacketListener.handleSetPlayerTeamPacket} on the
 * connection's {@code Scoreboard}): the team, the method, its collision rule when the packet carries parameters, and the
 * members (scoreboard names: player names, entity UUID strings, or anything else).
 *
 * @param method         {@code ClientboundSetPlayerTeamPacket} method: {@link #ADD}, {@link #REMOVE}, {@link #CHANGE},
 *                       {@link #JOIN}, {@link #LEAVE}
 * @param collisionRule  {@code Team.CollisionRule} ordinal ({@link #ALWAYS}, {@link #NEVER}, {@link #PUSH_OTHER_TEAMS},
 *                       {@link #PUSH_OWN_TEAM}) when the packet carries parameters (add, change), {@code -1} without
 * @param playersPayload the members ({@link #players()}) of add, join and leave
 */
public record TeamUpdate(String name, int method, int collisionRule, byte[] playersPayload) implements OutboundEvent {
    public static final int ADD = 0, REMOVE = 1, CHANGE = 2, JOIN = 3, LEAVE = 4;
    public static final int ALWAYS = 0, NEVER = 1, PUSH_OTHER_TEAMS = 2, PUSH_OWN_TEAM = 3;

    public TeamUpdate {
        playersPayload = playersPayload == null ? new byte[0] : playersPayload.clone();
    }

    public static TeamUpdate of(String name, int method, int collisionRule, List<String> players) {
        return new TeamUpdate(name, method, collisionRule, encode(players));
    }

    @Override
    public byte[] playersPayload() {
        return playersPayload.clone();
    }

    public List<String> players() {
        if (playersPayload.length == 0) return List.of();
        try (var in = new DataInputStream(new ByteArrayInputStream(playersPayload))) {
            int count = in.readInt();
            var players = new ArrayList<String>(count);
            for (int i = 0; i < count; i++) players.add(in.readUTF());
            return List.copyOf(players);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] encode(List<String> players) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(players.size());
            for (String player : players) out.writeUTF(player);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TeamUpdate that && name.equals(that.name) && method == that.method
                && collisionRule == that.collisionRule && players().equals(that.players());
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, method, collisionRule, players());
    }

    @Override
    public String toString() {
        return "TeamUpdate[name=" + name + ", method=" + method + ", collisionRule=" + collisionRule + ", players=" + players() + "]";
    }
}
