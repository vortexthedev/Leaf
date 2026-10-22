package ru.minecomplex.mac.engine.movementtick;

import java.util.EnumSet;
import java.util.Set;
import ru.minecomplex.mac.entity.attribute.Attribute;
import ru.minecomplex.mac.entity.attribute.AttributeModifier;
import ru.minecomplex.mac.entity.attribute.Identifier;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.entity.LivingVehicleState;
import ru.minecomplex.mac.mechanics.entity.MobBodyRotation;

/**
 * The root client's complete movement-relevant pig/strider/happy-ghast/nautilus tick, in {@code LivingEntity.tick} order:
 * the strider's own pre-tick cold update, base tick, {@code aiStep} with ridden travel and block effects, walk animation,
 * {@code BodyRotationControl.clientTick} (the happy ghast's variant), the rotation range checks and the type's tail
 * ({@code Strider.floatStrider}, the nautilus dash countdown). Contacts and exceptional base state are explicit facts.
 */
public final class LivingVehicleTickPhysics {
    /**
     * @param localAuthority       the local player controls the mob
     * @param saddled              {@code isSaddled()} (nautilus dash charge)
     * @param effectiveAi          the client's {@code isEffectiveAi()}
     * @param noAi                 {@code Mob.isNoAi()} (the strider then keeps its cold state)
     * @param baby                 {@code isBaby()} (walk animation scale)
     * @param exceptionalBaseState pending interpolation, dirty dimensions, movement-changing effects or other unresolved
     *                             base state; certifying false requires the owner to resolve them
     */
    public record Facts(boolean localAuthority, boolean saddled, boolean effectiveAi, boolean noAi, boolean baby,
                        float maxHeadYaw, boolean pushableQueryEmpty, boolean exceptionalBaseState, boolean navigationCanFloat) {
    }

    /** {@code Strider.SUFFOCATING_MODIFIER}: the float {@code -0.34F} widened, on the base value. */
    static final AttributeModifier SUFFOCATING = new AttributeModifier(Identifier.minecraft("suffocating"), -0.34F,
            AttributeModifier.Operation.ADD_MULTIPLIED_BASE);

    private final LivingVehicleState state;
    private final LivingVehicleBodyPhysics body;
    private final OtherVehicleRiding control;
    private final MobBodyRotation rotation = new MobBodyRotation();

    public LivingVehicleTickPhysics(LivingVehicleState state,
                                    LivingVehicleBodyPhysics body) {
        this.state = state;
        this.body = body;
        this.control = new OtherVehicleRiding(state.kind);
        if (body.state() != state.body) throw new IllegalArgumentException("Vehicle solver belongs to another body");
    }

    public OtherVehicleRiding control() {
        return control;
    }

    /** The control facts as travel reads them, from the live body: fluids of this tick, current attributes. */
    public OtherVehicleRiding.Facts controlFacts(Facts facts) {
        var p = state.body;
        return new OtherVehicleRiding.Facts(facts.localAuthority(), facts.saddled(), p.wasTouchingWater, state.suffocating,
                p.attributes.value(Attribute.MOVEMENT_SPEED), p.attributes.value(Attribute.FLYING_SPEED), body.blockSpeedFactor());
    }

    /** {@code Strider.setSuffocating}: the data flag and the movement speed modifier. */
    public void setSuffocating(boolean flag) {
        state.suffocating = flag;
        var speed = state.body.attributes.get(Attribute.MOVEMENT_SPEED);
        if (flag) speed.addOrUpdateModifier(SUFFOCATING);
        else speed.removeModifier(SUFFOCATING.id());
    }

    public Set<Unmodeled> tick(Facts facts, OtherVehicleRiding.Rider rider) {
        var gaps = EnumSet.noneOf(Unmodeled.class);
        gaps.addAll(state.tickGaps);
        body.clearUnmodeled();
        var p = state.body;
        var s = state.control;
        state.completeTick = false;
        if (!facts.localAuthority()) {
            gaps.add(Unmodeled.RIDING);
            state.tickGaps = Set.copyOf(gaps);
            return state.tickGaps;
        }
        if (facts.exceptionalBaseState() || !p.alive || p.passenger) gaps.add(Unmodeled.RIDING);
        if (!facts.pushableQueryEmpty()) gaps.add(Unmodeled.VEHICLE_ENTITIES);
        // tickNonPassenger: setOldPosAndRot, from 26.3 with the interpolation step (Entity.commonTick)
        p.oldPosition = p.position;
        s.yawOld = p.yRot;
        body.commonTickInterpolation();
        // Strider.tick before its superclass: a root strider is cold unless warm blocks or lava hold it
        if (state.kind == OtherVehicleRiding.Kind.STRIDER && !facts.noAi()) setSuffocating(!body.striderWarm());
        body.baseTick();
        s.movement = p.deltaMovement;
        s.yaw = p.yRot;
        s.pitch = p.xRot;
        s.onGround = p.onGround;
        body.riddenAiStep(control, s, () -> controlFacts(facts), rider, facts.effectiveAi(), facts.navigationCanFloat());
        // LivingEntity.aiStep on the client: calculateEntityAnimation, Mth.length(dx, 0.0, dz) for all of them (none is a
        // FlyingAnimal or an omnidirectional air mover, the happy ghast included)
        float distance = (float) Math.sqrt(sq(p.x() - p.oldPosition.x()) + 0.0 * 0.0 + sq(p.z() - p.oldPosition.z()));
        if (!p.passenger && p.alive) state.updateWalk(Math.min(distance * 4.0F, 1.0F), 0.4F, facts.baby() ? 3.0F : 1.0F);
        else state.stopWalk();
        // Mob.tickHeadTurn: BodyRotationControl.clientTick; the happy ghast's turns head and body with it while ridden
        var look = new MobBodyRotation.Look().set(p.yRot, s.headYaw, s.bodyYaw);
        if (state.kind == OtherVehicleRiding.Kind.HAPPY_GHAST) {
            look.headYaw = p.yRot;
            look.bodyYaw = look.headYaw;
        }
        rotation.tick(state.bodyRotation, look, p.oldPosition, p.position, facts.maxHeadYaw(), false, false);
        s.headYaw = look.headYaw;
        s.bodyYaw = look.bodyYaw;
        while (p.yRot - s.yawOld < -180.0F) s.yawOld -= 360.0F;
        while (p.yRot - s.yawOld >= 180.0F) s.yawOld += 360.0F;
        switch (state.kind) {
            case STRIDER -> body.floatStrider(control, s);
            case NAUTILUS, ZOMBIE_NAUTILUS -> control.advanceNautilusDash(s, p.firstTick);
            default -> {
            }
        }
        s.movement = p.deltaMovement;
        s.yaw = p.yRot;
        s.pitch = p.xRot;
        s.onGround = p.onGround;
        gaps.addAll(body.unmodeled());
        state.tickGaps = Set.copyOf(gaps);
        state.completeTick = true;
        return state.tickGaps;
    }

    private static double sq(double value) {
        return value * value;
    }
}
