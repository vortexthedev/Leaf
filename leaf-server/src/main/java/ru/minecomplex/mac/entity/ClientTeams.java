package ru.minecomplex.mac.entity;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import ru.minecomplex.mac.util.Tri;

/**
 * The connection's scoreboard teams as far as entity pushing reads them: names, collision rules and members
 * ({@code ClientPacketListener.handleSetPlayerTeamPacket} on {@code Scoreboard}). The scoreboard belongs to the
 * {@code ClientPacketListener}: a respawn keeps it, a new connection phase ({@code login}) starts empty.
 */
public final class ClientTeams {
    /** {@code Team.CollisionRule}, in wire order. */
    public enum Rule { ALWAYS, NEVER, PUSH_OTHER_TEAMS, PUSH_OWN_TEAM }

    /** {@code ClientboundSetPlayerTeamPacket} methods. */
    private static final int ADD = 0, REMOVE = 1, CHANGE = 2, JOIN = 3, LEAVE = 4;

    private static final class Team {
        final String name;
        Rule rule = Rule.ALWAYS;
        final Set<String> players = new LinkedHashSet<>();

        Team(String name) {
            this.name = name;
        }
    }

    private final Map<String, Team> byName = new HashMap<>();
    private final Map<String, Team> byPlayer = new HashMap<>();
    /** A handler threw ({@code removePlayerFromTeam} of a member of another team): the client disconnects. */
    private boolean broken;

    public ClientTeams copy() {
        var copy = new ClientTeams();
        for (Team team : byName.values()) {
            var copied = new Team(team.name);
            copied.rule = team.rule;
            copied.players.addAll(team.players);
            copy.byName.put(copied.name, copied);
        }
        byPlayer.forEach((player, team) -> copy.byPlayer.put(player, copy.byName.get(team.name)));
        copy.broken = broken;
        return copy;
    }

    /** {@code handleSetPlayerTeamPacket}. */
    public void apply(ClientboundSetPlayerTeamPacket teams) {
        // native modes and collision rules are declared in vanilla id order
        int method = teams.method();
        int collisionRule = teams.getParameters().map(info -> info.collisionRule() == null ? -1 : info.collisionRule().ordinal()).orElse(-1);
        java.util.Collection<String> players = teams.getPlayers() == null ? List.of() : teams.getPlayers();
        apply(teams.getName(), method, collisionRule, players);
    }

    private void apply(String name, int method, int collisionRule, java.util.Collection<String> players) {
        if (broken) return;
        Team team;
        if (method == ADD) {
            // Scoreboard.addPlayerTeam keeps an existing team of that name
            team = byName.computeIfAbsent(name, Team::new);
        } else {
            team = byName.get(name);
            // an unknown team: the client logs and ignores the whole packet
            if (team == null) return;
        }
        if ((method == ADD || method == CHANGE) && collisionRule >= 0) {
            team.rule = Rule.values()[collisionRule];
        }
        if (method == ADD || method == JOIN) {
            for (String player : players) {
                // Scoreboard.addPlayerToTeam: out of its old team first
                Team old = byPlayer.get(player);
                if (old != null) old.players.remove(player);
                byPlayer.put(player, team);
                team.players.add(player);
            }
        } else if (method == LEAVE) {
            for (String player : players) {
                if (byPlayer.get(player) != team) {
                    broken = true;
                    return;
                }
                byPlayer.remove(player);
                team.players.remove(player);
            }
        }
        if (method == REMOVE) {
            byName.remove(team.name);
            for (String player : team.players) byPlayer.remove(player);
        }
    }

    /** Whether no team exists (every collision rule is {@code ALWAYS}). */
    public boolean empty() {
        return byName.isEmpty() && !broken;
    }

    /**
     * The team part of {@code EntitySelector.pushableBy(pusher).test(pushed)} on the client, where the pushed entity is the
     * local player: the pusher's rule {@code NEVER} pushes nothing, the player's {@code NEVER} is never pushed, and
     * {@code PUSH_OWN_TEAM}/{@code PUSH_OTHER_TEAMS} of either side split by being in the same team
     * ({@code Team.isAlliedTo}).
     *
     * @param pusher scoreboard name of the pushing entity, {@code null} when not known
     * @param pushed scoreboard name of the local player, {@code null} when not known
     */
    public Tri pushes(String pusher, String pushed) {
        if (broken) return Tri.EITHER;
        if (byName.isEmpty()) return Tri.TRUE;
        Team own = pusher == null ? null : byPlayer.get(pusher);
        Team theirs = pushed == null ? null : byPlayer.get(pushed);
        if (pusher == null || pushed == null) {
            // the known side alone may decide
            if (pusher != null && rule(own) == Rule.NEVER || pushed != null && rule(theirs) == Rule.NEVER) return Tri.FALSE;
            return Tri.EITHER;
        }
        Rule ownRule = rule(own), theirRule = rule(theirs);
        if (ownRule == Rule.NEVER || theirRule == Rule.NEVER) return Tri.FALSE;
        boolean sameTeam = own != null && own == theirs;
        if ((ownRule == Rule.PUSH_OWN_TEAM || theirRule == Rule.PUSH_OWN_TEAM) && sameTeam) return Tri.FALSE;
        return Tri.of(ownRule != Rule.PUSH_OTHER_TEAMS && theirRule != Rule.PUSH_OTHER_TEAMS || sameTeam);
    }

    private static Rule rule(Team team) {
        return team == null ? Rule.ALWAYS : team.rule;
    }
}
