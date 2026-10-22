package ru.minecomplex.mac.player.state;

/**
 * What the local player last sent ({@code LocalPlayer.sendPosition} and the packets before it): the hidden send state of
 * {@code LocalPlayer}, retained across ticks and forked with a hypothesis.
 */
public final class SendState {
    public PlayerInput lastSentInput = PlayerInput.EMPTY;
    public boolean wasSprinting;
    public boolean wasShiftKeyDown;
    public double xLast, yLast, zLast;
    public float yRotLast, xRotLast;
    public int positionReminder;
    public boolean lastOnGround, lastHorizontalCollision;
    /** {@code autoJumpEnabled}: the auto-jump option as the last position send read it. */
    public boolean autoJumpEnabled = true;

    public SendState copy() {
        SendState copy = new SendState();
        copy.lastSentInput = lastSentInput;
        copy.wasSprinting = wasSprinting;
        copy.wasShiftKeyDown = wasShiftKeyDown;
        copy.xLast = xLast;
        copy.yLast = yLast;
        copy.zLast = zLast;
        copy.yRotLast = yRotLast;
        copy.xRotLast = xRotLast;
        copy.positionReminder = positionReminder;
        copy.lastOnGround = lastOnGround;
        copy.lastHorizontalCollision = lastHorizontalCollision;
        copy.autoJumpEnabled = autoJumpEnabled;
        return copy;
    }
}
