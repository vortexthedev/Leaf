package ru.minecomplex.mac.player.state;

/** The seven vanilla Input booleans, after client key mapping options have been applied. */
public record PlayerInput(boolean forward, boolean backward, boolean left, boolean right,
                          boolean jump, boolean shift, boolean sprint) {
    public static final PlayerInput EMPTY = fromMask(0);

    public static PlayerInput fromMask(int mask) {
        if ((mask & ~127) != 0) throw new IllegalArgumentException("Invalid input mask " + mask);
        return new PlayerInput((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0,
                (mask & 16) != 0, (mask & 32) != 0, (mask & 64) != 0);
    }

    public int mask() {
        return (forward ? 1 : 0) | (backward ? 2 : 0) | (left ? 4 : 0) | (right ? 8 : 0)
                | (jump ? 16 : 0) | (shift ? 32 : 0) | (sprint ? 64 : 0);
    }

    public PlayerInput withJump() {
        return new PlayerInput(forward, backward, left, right, true, shift, sprint);
    }
}
