package ru.minecomplex.mac.entity;

import java.util.Set;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.engine.movementtick.OtherVehicleRiding;
import ru.minecomplex.mac.mechanics.entity.MobBodyRotation;
import ru.minecomplex.mac.player.state.PlayerState;

/**
 * Persistent pig/strider/happy-ghast/nautilus body, control and animation fields of one client entity generation,
 * copied with the hypothesis that owns it.
 */
public final class LivingVehicleState {
    public final Object generation;
    public final OtherVehicleRiding.Kind kind;
    public PlayerState body;
    public OtherVehicleRiding.State control = new OtherVehicleRiding.State();
    public MobBodyRotation.State bodyRotation = new MobBodyRotation.State();
    /** {@code WalkAnimationState}: the strider's seat bobs with it. */
    public float walkSpeedOld, walkSpeed, walkPosition, walkPositionScale = 1.0F;
    /** {@code Strider.isSuffocating()}, which the client also sets itself every tick. */
    public boolean suffocating;
    public boolean completeTick;
    /** Historical unresolved movement persists until the owner supplies a newly resolved body baseline. */
    public Set<Unmodeled> tickGaps = Set.of();

    public LivingVehicleState(Object generation, OtherVehicleRiding.Kind kind, PlayerState body) {
        this.generation = java.util.Objects.requireNonNull(generation);
        this.kind = java.util.Objects.requireNonNull(kind);
        this.body = java.util.Objects.requireNonNull(body);
    }

    /** The owner installed a newly resolved body baseline: earlier unresolved movement no longer affects it. */
    public void resolvedBaseline() {
        tickGaps = Set.of();
    }

    /** Whether the last tick was a complete, resolved whole tick. */
    public boolean completeTick() {
        return completeTick;
    }

    /** {@code WalkAnimationState.update(target, factor, positionScale)}. */
    public void updateWalk(float target, float factor, float positionScale) {
        walkSpeedOld = walkSpeed;
        walkSpeed = walkSpeed + (target - walkSpeed) * factor;
        walkPosition = walkPosition + walkSpeed;
        walkPositionScale = positionScale;
    }

    /** {@code WalkAnimationState.stop()}. */
    public void stopWalk() {
        walkSpeedOld = 0.0F;
        walkSpeed = 0.0F;
        walkPosition = 0.0F;
    }

    public LivingVehicleState copy() {
        var s = new LivingVehicleState(generation, kind, body.copy());
        s.control = control.copy();
        s.bodyRotation = bodyRotation.copy();
        s.walkSpeedOld = walkSpeedOld;
        s.walkSpeed = walkSpeed;
        s.walkPosition = walkPosition;
        s.walkPositionScale = walkPositionScale;
        s.suffocating = suffocating;
        s.completeTick = completeTick;
        s.tickGaps = tickGaps;
        return s;
    }
}
