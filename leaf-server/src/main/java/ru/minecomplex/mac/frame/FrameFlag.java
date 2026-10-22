package ru.minecomplex.mac.frame;

/**
 * Structural observations about a client tick frame. None of them occurs for a vanilla client unless noted.
 */
public enum FrameFlag {
    /**
     * A pong or a packet-handler response arrived after the tick's own packets had started.
     */
    RESPONSE_INSIDE_TICK,
    /**
     * The window was widened to cover acknowledgements that arrived inside the tick.
     */
    WINDOW_WIDENED,
    /**
     * More than one player movement packet in one tick.
     */
    MULTIPLE_MOVEMENTS,
    /**
     * Vanilla-possible: a {@code Rot} right after the previous tick was taken as the response to a relative
     * {@code player_rotation}; it may instead be this tick's own rotation packet.
     */
    AMBIGUOUS_ROTATION_RESPONSE
}
