package ru.minecomplex.mac.fixture.outbound;

/**
 * {@code login} (play): the client creates a new level and player. Starts a world epoch.
 *
 * @param dimension   dimension (level) key
 * @param minY        lowest block y of the dimension type
 * @param height      build height of the dimension type
 * @param chunkRadius server view distance ({@code ClientChunkCache} storage is sized from it)
 * @param gameMode    {@code GameType} id the client passes to {@code setLocalMode}
 * @param canHaveWeather weather gate from the received dimension type (sky light and no ceiling); EITHER if absent
 * @param localName   the name of the profile the server logged the client in with ({@code getLocalGameProfile()}), which
 *                    names the local player on the scoreboard; empty when the adapter did not know it
 */
public record JoinGame(int playerEntityId, String dimension, int minY, int height, int chunkRadius, int gameMode,
                       String localName, ru.minecomplex.mac.util.Tri canHaveWeather) implements OutboundEvent {

    public JoinGame(int playerEntityId, String dimension, int minY, int height, int chunkRadius, int gameMode, String localName) {
        this(playerEntityId, dimension, minY, height, chunkRadius, gameMode, localName, ru.minecomplex.mac.util.Tri.EITHER);
    }

    public JoinGame(int playerEntityId, String dimension, int minY, int height, int chunkRadius, int gameMode) {
        this(playerEntityId, dimension, minY, height, chunkRadius, gameMode, "");
    }

    public JoinGame {
        localName = localName == null ? "" : localName;
        canHaveWeather = canHaveWeather == null ? ru.minecomplex.mac.util.Tri.EITHER : canHaveWeather;
    }

    /** Survival. */
    public JoinGame(int playerEntityId, String dimension, int minY, int height, int chunkRadius) {
        this(playerEntityId, dimension, minY, height, chunkRadius, 0);
    }
}
