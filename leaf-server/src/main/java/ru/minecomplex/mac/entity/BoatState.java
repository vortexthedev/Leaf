package ru.minecomplex.mac.entity;

import ru.minecomplex.mac.engine.movementtick.VehiclePacketState;
import ru.minecomplex.mac.entity.interpolation.BoatInterpolation;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;

/**
 * The client's {@code AbstractBoat} fields of one entity generation in one client tick hypothesis: its body (position,
 * velocity, rotation, collisions and fluids are the {@code Entity} fields of {@link #body}) and the boat's own state.
 */
public final class BoatState {
    /** {@code AbstractBoat.Status}. */
    public enum Status { IN_WATER, UNDER_WATER, UNDER_FLOWING_WATER, ON_LAND, IN_AIR }

    public final Object generation;
    public PlayerState body;
    public BoatInterpolation interpolation = new BoatInterpolation();
    public float eyeHeight, outOfControlTicks;
    public boolean noGravity;
    public int tickCount;
    /** {@code status} and {@code oldStatus}; {@code null} until the first tick (and as vanilla's absent status). */
    public Status status, oldStatus;
    public float deltaRotation, landFriction;
    public double waterLevel, lastYd;
    /** {@code paddleState}: the paddles the boat shows (and sends while a player drives). */
    public boolean leftPaddle, rightPaddle;
    /** Set by LocalPlayer.rideTick after this boat's tick; consumed by the next boat tick. */
    public PlayerInput input = PlayerInput.EMPTY;
    /**
     * The fields the boat keeps between rides ({@code deltaRotation}, the input) come from a ride that started uncertain:
     * the next ride's start is not known either.
     */
    public boolean carriedUncertain;

    public BoatState(Object generation, float width, float height, float eyeHeight) {
        this.generation = java.util.Objects.requireNonNull(generation);
        this.eyeHeight = eyeHeight;
        body = new PlayerState();
        body.fixedBodyDimensions = true;
        body.bodyWidth = width;
        body.bodyHeight = height;
    }

    /** {@code status == UNDER_WATER || status == UNDER_FLOWING_WATER}. */
    public boolean underwater() {
        return status == Status.UNDER_WATER || status == Status.UNDER_FLOWING_WATER;
    }

    /** Immutable packet view from this generation's simulated body/interpolation, at the selected send phase. */
    public VehiclePacketState packetState(ru.minecomplex.mac.util.Tri authority) {
        var target = interpolation.target();
        return new VehiclePacketState(generation, authority, body.position, body.yRot, body.xRot,
                target, interpolation.active(), body.onGround);
    }

    public BoatState copy() {
        var copy = new BoatState(generation, body.bodyWidth, body.bodyHeight, eyeHeight);
        copy.body = body.copy();
        copy.interpolation = interpolation.copy();
        copy.noGravity = noGravity;
        copy.outOfControlTicks = outOfControlTicks;
        copy.tickCount = tickCount;
        copy.status = status;
        copy.oldStatus = oldStatus;
        copy.deltaRotation = deltaRotation;
        copy.landFriction = landFriction;
        copy.waterLevel = waterLevel;
        copy.lastYd = lastYd;
        copy.leftPaddle = leftPaddle;
        copy.rightPaddle = rightPaddle;
        copy.input = input;
        copy.carriedUncertain = carriedUncertain;
        return copy;
    }
}
