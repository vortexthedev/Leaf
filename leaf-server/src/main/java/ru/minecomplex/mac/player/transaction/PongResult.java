package ru.minecomplex.mac.player.transaction;

/**
 * Outcome of a pong.
 *
 * @param marker  the acknowledged marker, {@code null} for {@link Kind#FOREIGN}
 * @param skipped markers that were still outstanding before it (always 0 for a vanilla client)
 */
public record PongResult(Kind kind, Marker marker, int skipped) {
    private static final PongResult FOREIGN = new PongResult(Kind.FOREIGN, null, 0);

    public enum Kind {
        /** Acknowledges our marker (and every earlier one). */
        ACKNOWLEDGED,
        /** Not one of our outstanding markers: belongs to another plugin, must be passed on. */
        FOREIGN
    }

    public static PongResult foreign() {
        return FOREIGN;
    }

    public static PongResult acknowledged(Marker marker, int skipped) {
        return new PongResult(Kind.ACKNOWLEDGED, marker, skipped);
    }

    public boolean ours() {
        return kind == Kind.ACKNOWLEDGED;
    }
}
