package ru.minecomplex.mac.player.connection;

import java.util.UUID;

/**
 * What a connection's packets are read with that the packets themselves do not carry: the profile the server logged
 * the client in with (its scoreboard name, its own {@code PlayerInfo} entry).
 *
 * @param localName the local player's profile name ({@code null} or empty: not known)
 * @param localUuid the local player's profile id ({@code null}: not known)
 */
public record PlayerConnectionContext(String localName, UUID localUuid) {
    /** Placeholder profile id of a connection whose profile is not known (offline replay of synthetic sessions). */
    public static final UUID UNKNOWN_PROFILE = new UUID(0L, 0L);

    public PlayerConnectionContext {
        if (localName == null) localName = "";
        if (localUuid == null) localUuid = UNKNOWN_PROFILE;
    }
}
