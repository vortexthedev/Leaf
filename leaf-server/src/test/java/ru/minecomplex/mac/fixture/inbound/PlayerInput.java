package ru.minecomplex.mac.fixture.inbound;

/**
 * {@code player_input}: the key state the client used in the tick it is sent in. Sent only when it changes. Bit
 * layout is the vanilla one ({@code Input.STREAM_CODEC}): forward, backward, left, right, jump, shift, sprint.
 */
public record PlayerInput(int flags) implements InboundEvent {
    public static final int FORWARD = 1;
    public static final int BACKWARD = 1 << 1;
    public static final int LEFT = 1 << 2;
    public static final int RIGHT = 1 << 3;
    public static final int JUMP = 1 << 4;
    public static final int SHIFT = 1 << 5;
    public static final int SPRINT = 1 << 6;

    public static final PlayerInput NONE = new PlayerInput(0);

    public PlayerInput {
        if ((flags & ~0x7F) != 0) {
            throw new IllegalArgumentException("Unknown input bits: " + Integer.toBinaryString(flags));
        }
    }

    public boolean forward() {
        return (flags & FORWARD) != 0;
    }

    public boolean backward() {
        return (flags & BACKWARD) != 0;
    }

    public boolean left() {
        return (flags & LEFT) != 0;
    }

    public boolean right() {
        return (flags & RIGHT) != 0;
    }

    public boolean jump() {
        return (flags & JUMP) != 0;
    }

    public boolean shift() {
        return (flags & SHIFT) != 0;
    }

    public boolean sprint() {
        return (flags & SPRINT) != 0;
    }
}
