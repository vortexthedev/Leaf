package ru.minecomplex.mac.fixture.outbound;

/**
 * {@code respawn}. The client always recreates the player, but recreates the level (and drops every chunk) only
 * when {@code dimension} differs from its current one ({@code ClientPacketListener.handleRespawn}).
 *
 * @param dimension  dimension (level) key
 * @param dataToKeep vanilla keep flags (1 = attributes, 2 = entity data incl. delta movement)
 * @param minY       lowest block y of the dimension type
 * @param height     build height of the dimension type
 * @param canHaveWeather dimension-type weather gate; EITHER for older recordings/adapters
 * @param gameMode   {@code GameType} id the client passes to {@code setLocalMode}
 */
public record Respawn(String dimension, int dataToKeep, int minY, int height, int gameMode, ru.minecomplex.mac.util.Tri canHaveWeather) implements OutboundEvent {
    public Respawn(String dimension, int dataToKeep, int minY, int height, int gameMode) {
        this(dimension, dataToKeep, minY, height, gameMode, ru.minecomplex.mac.util.Tri.EITHER);
    }

    public Respawn {
        canHaveWeather = canHaveWeather == null ? ru.minecomplex.mac.util.Tri.EITHER : canHaveWeather;
    }

    public static final int KEEP_ATTRIBUTES = 1;
    public static final int KEEP_ENTITY_DATA = 2;

    /** Survival. */
    public Respawn(String dimension, int dataToKeep, int minY, int height) {
        this(dimension, dataToKeep, minY, height, 0);
    }

    public boolean keeps(int data) {
        return (dataToKeep & data) != 0;
    }
}
