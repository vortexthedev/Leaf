package ru.minecomplex.mac.engine;

import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;
import net.minecraft.core.component.DataComponents;
import ru.minecomplex.mac.inventory.item.ItemMechanics;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import ru.minecomplex.mac.replica.CompensatedEntities;
import ru.minecomplex.mac.packet.MoveKind;
import ru.minecomplex.mac.packet.Packets;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import ru.minecomplex.mac.entity.EntityAttachment;
import ru.minecomplex.mac.entity.EntityTypes;
import ru.minecomplex.mac.entity.MobData;
import ru.minecomplex.mac.entity.PassengerGraph;
import ru.minecomplex.mac.entity.Pose;
import ru.minecomplex.mac.entity.attribute.Attribute;
import ru.minecomplex.mac.entity.attribute.AttributeMap;
import net.minecraft.world.item.ItemStack;
import ru.minecomplex.mac.inventory.item.Stacks;
import ru.minecomplex.mac.engine.movementtick.HorsePassengerPhysics;
import ru.minecomplex.mac.engine.movementtick.HorsePlayerRide;
import ru.minecomplex.mac.engine.movementtick.HorsePlayerTick;
import ru.minecomplex.mac.engine.movementtick.MovementTickerHorse;
import ru.minecomplex.mac.entity.HorseState;
import ru.minecomplex.mac.engine.movementtick.LivingVehicleBodyPhysics;
import ru.minecomplex.mac.engine.movementtick.LivingVehiclePlayerRide;
import ru.minecomplex.mac.engine.movementtick.LivingVehiclePlayerTick;
import ru.minecomplex.mac.entity.LivingVehicleState;
import ru.minecomplex.mac.engine.movementtick.LivingVehicleTickPhysics;
import ru.minecomplex.mac.engine.movementtick.OtherVehicleRiding;
import ru.minecomplex.mac.player.state.ClientPlayerData;
import ru.minecomplex.mac.engine.movementtick.VehiclePacketState;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.engine.movementtick.MovementTickerBoat;
import ru.minecomplex.mac.engine.movementtick.BoatPassengerPhysics;
import ru.minecomplex.mac.engine.movementtick.BoatPlayerTick;
import ru.minecomplex.mac.entity.BoatState;
import ru.minecomplex.mac.engine.movementtick.MovementTickerPlayer;
import ru.minecomplex.mac.engine.movementtick.TickPackets;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.mechanics.collision.BlockCollisions;
import ru.minecomplex.mac.replica.CompensatedWorld;

/**
 * The local player's ride for {@link MovementCheckRunner}: the root vehicle and its local passenger are ticked by the whole-tick
 * engines (root before passenger, as {@code ClientLevel} ticks them) and the vehicle position the engine would send is
 * compared with the client's {@code move_vehicle}. As with the player, every tick is measured on its own: the vehicle is
 * moved to the reported position after the tick and the simulated velocity is kept.
 *
 * <p>A ride starts from the entity tracker's pre-tick transform and client velocity when resolved; otherwise its first
 * packet seeds an explicitly uncertain baseline. Uncontrolled roots remain in the remote tracker, but their passenger
 * still runs {@code rideTick}. Nested rides, missing remote seat clocks and unresolved entity queries remain uncertain.
 *
 * <p>Boats and rafts run {@link BoatPlayerTick}; horses, donkeys, mules, skeleton and zombie horses, camels and camel husks
 * run {@link HorsePlayerTick}; pigs, striders, happy ghasts and both nautiluses run {@link LivingVehiclePlayerTick}; all
 * with the mob's synced data, attributes, equipment and the level time as the client holds them.
 */
final class VehicleCheckRunner {

    /**
     * The ride part of a {@link MovementCheckRunner.TickReport}.
     */
    record Outcome(MovementCheckRunner.Status status, double offset, Vec3d predicted, Vec3d reported, Set<Unmodeled> gaps, String note, String vehicle) {
    }

    private final EntityTypes types;

    /**
     * The root vehicle generation this probe seeded and follows; {@code null} between controlled rides.
     */
    private Object following;

    /**
     * The same passenger lost its steering item; controller clocks stay bound to that entity generation.
     */
    private Object inactiveFollowing;

    /**
     * Data assignments already copied into the followed horse's client-side fields ({@link CompensatedEntities#assigned}).
     */
    private long appliedFlags, appliedDash, appliedPoseTick, appliedPose;

    private long appliedBoost, appliedSuffocating, appliedNautilusDash;

    /**
     * Ticks left in which a boost or dash started before the ride may still change the mount's movement.
     */
    private int boostUnknownTicks, dashUnknownTicks;

    /**
     * The attribute snapshots already applied to the followed horse's body.
     */
    private java.util.Map<String, ru.minecomplex.mac.entity.attribute.AttributeSnapshot> appliedAttributes = java.util.Map.of();

    VehicleCheckRunner() {
        this.types = EntityTypes.get();
    }

    private java.util.function.Predicate<ru.minecomplex.mac.collision.Aabb> othersNear = box -> true;

    private java.util.function.Consumer<Boolean> riderMoves = moving -> {
    };

    /**
     * Where the simulated vehicle is now, while it moves this tick ({@code null}: no vehicle simulated).
     */
    private java.util.function.Supplier<Vec3d> vehiclePosition = () -> null;

    /**
     * The simulated vehicle's position now (its world-border query reads it), {@code null} when none is simulated.
     */
    Vec3d vehiclePosition() {
        return vehiclePosition.get();
    }

    /**
     * Where the mount's {@code pushEntities} box ({@code getBoundingBox()} after its travel) can be: its box before the
     * tick, widened by its speed and a block.
     */
    private static ru.minecomplex.mac.collision.Aabb pushReach(PlayerState body) {
        double speed = Math.sqrt(body.deltaMovement.lengthSqr());
        return body.boundingBox().inflate(speed + 1.0);
    }

    /**
     * Whether this frame's ride ran the passenger's own tick from a known state (its velocity after the tick is then the
     * client's: {@code rideTick} starts it from zero); false when the ride was skipped or the rider's yaw was not certain.
     */
    boolean riderSimulated() {
        return riderSimulated;
    }

    private boolean riderSimulated;


    private boolean[] riderSimulatedCheck;

    /**
     * The driven vehicle's body this frame's ride simulated from a known velocity ({@code null}: none), which a block
     * entity of the same client tick (a geyser) may still push; {@link #pushed} keeps the ride's velocity with it.
     */
    PlayerState simulatedBody() {
        return simulatedBody;
    }

    private PlayerState simulatedBody;

    /**
     * A block entity changed the simulated body's velocity after the ride ({@link #simulatedBody}).
     */
    void pushed(Vec3d velocity) {
        if (simulatedBody == null)
            return;
        simulatedBody.deltaMovement = velocity;
        if (rideVelocity != null)
            rideVelocity = velocity;
    }

    /**
     * The frame was not a ride tick: the next ride is seeded again.
     */
    void reset() {
        simulatedBody = null;
        following = null;
        inactiveFollowing = null;
        rideVelocity = null;
        rideVelocityEstimate = null;
        rideTransform = null;
        borderVelocityGuess = false;
    }

    /**
     * A tick of this ride did not know the world border's phase near the vehicle: the velocity it left is a guess.
     */
    private boolean borderVelocityGuess;

    /**
     * A compared ride tick after one that did not know the border's phase reports that gap until a tick moves the vehicle
     * bit for bit as the client did from the same transform (its velocity is then the client's: an axis a collision
     * stopped is zeroed on both sides); a ride not simulated starts again from a seed.
     */
    Outcome borderCarried(Outcome outcome, Set<Unmodeled> queryGaps) {
        if (outcome.status() != MovementCheckRunner.Status.COMPARED) {
            borderVelocityGuess = false;
            return outcome;
        }
        // the border's gap comes with the vehicle's own queries (the environment's) or the ride's
        boolean own = outcome.gaps().contains(Unmodeled.WORLD_BORDER_CLOCK) || queryGaps.contains(Unmodeled.WORLD_BORDER_CLOCK);
        if (borderVelocityGuess && !own && outcome.reported() != null && outcome.offset() == 0.0)
            borderVelocityGuess = false;
        Outcome result = outcome;
        if (borderVelocityGuess) {
            Set<Unmodeled> gaps = EnumSet.noneOf(Unmodeled.class);
            gaps.addAll(outcome.gaps());
            gaps.add(Unmodeled.WORLD_BORDER_CLOCK);
            String carried = "скорость транспорта после столкновения с границей мира неизвестной фазы неизвестна";
            result = new Outcome(outcome.status(), outcome.offset(), outcome.predicted(), outcome.reported(), gaps, outcome.note() == null || outcome.note().isEmpty() ? carried : outcome.note() + "; " + carried, outcome.vehicle());
        }
        if (own)
            borderVelocityGuess = true;
        return result;
    }

    /**
     * A followed mount after a ride tick: its transform and interpolation ({@code null}: none); {@code exact} when they
     * are the client's (not when the ride started from an assumed velocity, or the client reported another interpolation
     * target: the transform is then this model's estimate while it interpolates).
     */
    record RideTransform(Vec3d position, float yaw, float pitch, ru.minecomplex.mac.entity.interpolation.EntityInterpolation interpolation, boolean exact, boolean onGround) {
    }

    /**
     * The followed mount after this frame's ride ({@code null}: not simulated).
     */
    RideTransform rideTransform() {
        return rideTransform;
    }

    private RideTransform rideTransform;

    /**
     * The client reported an interpolation target other than the simulated one: where the mount is stays unknown until a
     * tick reports its position.
     */
    private boolean interpolationDiverged;

    /**
     * The first driven tick starts from the client's transform of the mount before it (position, rotation, the ground
     * flag its last packet gave, an interpolation still running: the client then reports the interpolation target, not
     * where the mount is). An uncertain pre-tick estimate still precedes the tick; the reported post-tick transform
     * is only a fallback when no tracker snapshot exists.
     */
    private void seedTransform(CompensatedEntities entities, int rootId, PlayerState body, ServerboundMoveVehiclePacket after) {
        interpolationDiverged = false;
        var motions = entities.motions();
        var known = motions == null ? null : motions.rideStart(rootId);
        var start = known != null ? known : motions == null ? null : motions.rideStartEstimate(rootId);
        seedTransformKnown = known != null && !motions.rideStartUncertain(rootId);
        if (start != null) {
            body.position = start.body.position;
            body.yRot = start.body.yRot;
            body.xRot = start.body.xRot;
            body.onGround = start.body.onGround;
            body.interpolation = start.interpolation.copy();
            return;
        }
        body.position = new Vec3d(after.movingTo().position().x(), after.movingTo().position().y(), after.movingTo().position().z());
        body.yRot = after.movingTo().yRot();
        body.xRot = after.movingTo().xRot();
        body.onGround = after.onGround();
        body.interpolation = null;
    }

    /**
     * The mount after a ride tick follows the client: the reported transform, unless the mount still interpolates (the
     * client then reports the interpolation target and keeps its own transform): the simulated one stays if the target
     * matches, else it is unknown until a tick reports the position.
     */
    private void follow(PlayerState body, ServerboundMoveVehiclePacket predicted, ServerboundMoveVehiclePacket reported) {
        if (body.interpolation == null || !body.interpolation.active()) {
            body.position = new Vec3d(reported.movingTo().position().x(), reported.movingTo().position().y(), reported.movingTo().position().z());
            body.yRot = reported.movingTo().yRot();
            body.xRot = reported.movingTo().xRot();
            body.onGround = reported.onGround();
            interpolationDiverged = false;
        } else if (!Packets.same(reported, predicted)) {
            interpolationDiverged = true;
        }
    }

    /**
     * {@code ServerboundMoveVehiclePacket.fromEntity} of a driven living mount: the interpolation target while it interpolates.
     */
    private static VehiclePacketState mountPacket(Object generation, PlayerState body) {
        var interpolation = body.interpolation;
        var target = interpolation == null ? null : new VehiclePacketState.Target(interpolation.targetPosition(body), interpolation.targetYaw(body), interpolation.targetPitch(body));
        return new VehiclePacketState(generation, Tri.TRUE, body.position, body.yRot, body.xRot, target, interpolation != null && interpolation.active(), body.onGround);
    }

    /**
     * The followed vehicle's delta movement after this frame's ride ({@code null}: not simulated).
     */
    Vec3d rideVelocity() {
        return rideVelocity;
    }

    /**
     * Simulated delta movement even when the starting velocity lacks a certificate.
     */
    Vec3d rideVelocityEstimate() {
        return rideVelocityEstimate;
    }

    String diagnosticState() {
        return "vehicle seedVelocityUnknown=" + seedVelocityUnknown + " seedTransformKnown=" + seedTransformKnown + " interpolationDiverged=" + interpolationDiverged + " following=" + (following != null) + " inactiveFollowing=" + (inactiveFollowing != null) + " velocity=" + rideVelocity + " estimate=" + rideVelocityEstimate + " transform=" + rideTransform + " boostUnknownTicks=" + boostUnknownTicks + " appliedBoost=" + appliedBoost;
    }

    private Vec3d rideVelocity;

    private Vec3d rideVelocityEstimate;

    /**
     * The followed mount started from a delta movement the model did not follow (its client movement before the ride):
     * reported until the mount and the client's vehicle are both at rest.
     */
    private boolean seedVelocityUnknown;

    /**
     * A resolved client-world transform, rather than a seed from the reported packet target.
     */
    private boolean seedTransformKnown;

    private Vec3d lastReportedVehicle;

    /**
     * The delta movement a living mount has when the local player starts to drive it: the one the client kept for it
     * ({@code EntityMotions.velocity}: spawn, motion and teleport packets, the remote slowing, the ride it last had), else
     * a resting one with the uncertainty reported.
     */
    private Vec3d startVelocity(CompensatedEntities entities, int rootId, ServerboundMoveVehiclePacket after) {
        var motions = entities.motions();
        Vec3d known = motions == null ? null : motions.rideStartVelocity(rootId);
        Vec3d estimate = motions == null ? null : motions.rideStartVelocityEstimate(rootId);
        if (motions != null)
            motions.takeRideVelocity(rootId);
        // a start a step behind the client's (a late move of the mount) is no better known than an assumed velocity
        seedVelocityUnknown = known == null || motions != null && motions.rideStartUncertain(rootId);
        lastReportedVehicle = null;
        return known != null ? known : estimate != null ? estimate : restingVelocity(after);
    }

    /**
     * A {@code set_entity_motion} the server sent for the driven mount since its last tick.
     */
    private void rideMotion(CompensatedEntities entities, int rootId, PlayerState body) {
        var motions = entities.motions();
        var known = motions == null ? null : motions.rideStart(rootId);
        var start = known != null ? known : motions == null ? null : motions.rideStartEstimate(rootId);
        if (start != null) {
            // The marker-compensated root before this tick includes handlers received while already driving it,
            // notably teleport_entity. It must reach the ridden body before the body's own interpolation step.
            body.position = start.body.position;
            body.yRot = start.body.yRot;
            body.xRot = start.body.xRot;
            body.onGround = start.body.onGround;
            body.interpolation = start.interpolation.copy();
            seedTransformKnown = known != null && !motions.rideStartUncertain(rootId);
            if (!seedTransformKnown || motions.rideStartVelocity(rootId) == null)
                seedVelocityUnknown = true;
        } else {
            seedTransformKnown = false;
            seedVelocityUnknown = true;
        }
        Vec3d set = motions == null ? null : motions.takeRideVelocity(rootId);
        if (set != null)
            body.deltaMovement = set;
    }

    /**
     * A tick after the client reported another interpolation target than the simulated one.
     */
    private static void divergedInterpolation(boolean diverged, Set<Unmodeled> gaps, List<String> notes) {
        if (!diverged)
            return;
        gaps.add(Unmodeled.RIDING);
        notes.add("интерполяция транспорта разошлась с клиентом");
    }

    /**
     * The unknown start of the ride until a tick in which the client's vehicle did not move and the simulation (from the
     * assumed velocity) did not either: every axis then ended at rest or against a collision on both sides (the move
     * zeroes a blocked axis), so the velocities agree from there.
     */
    private void startUncertainty(Set<Unmodeled> gaps, List<String> notes, ServerboundMoveVehiclePacket predicted, ServerboundMoveVehiclePacket reported, boolean physicalReport) {
        Vec3d at = reported == null ? null : new Vec3d(reported.movingTo().position().x(), reported.movingTo().position().y(), reported.movingTo().position().z());
        if (seedVelocityUnknown && physicalReport && at != null && at.equals(lastReportedVehicle) && predicted != null && at.equals(new Vec3d(predicted.movingTo().position().x(), predicted.movingTo().position().y(), predicted.movingTo().position().z()))) {
            seedVelocityUnknown = false;
        }
        // An unchanged interpolation target does not prove rest; neither endpoint may be a target.
        lastReportedVehicle = physicalReport ? at : null;
        if (seedVelocityUnknown) {
            gaps.add(Unmodeled.RIDING);
            notes.add("состояние транспорта до посадки неизвестно");
        }
    }

    /**
     * @param othersNear whether an entity other than the ride's own may be inside a box ({@code EntityMotions.othersNear}):
     *                   the vehicle's pushable-entity queries are resolved empty only when none may be
     */
    Outcome tick(ClientTickFrame frame, CompensatedWorld level, MovementEnvironment env, PlayerState p, LocalPlayerState local, ClientPlayerData data, java.util.function.Predicate<ru.minecomplex.mac.collision.Aabb> othersNear) {
        return tick(frame, level, env, p, local, data, othersNear, moving -> {
        });
    }

    /**
     * @param riderMoves told {@code true} while the passenger's own tick runs (its entity queries are the player's,
     *                   {@code getEntityCollisions(player, box)}), {@code false} while the vehicle moves
     */
    Outcome tick(ClientTickFrame frame, CompensatedWorld level, MovementEnvironment env, PlayerState p, LocalPlayerState local, ClientPlayerData data, java.util.function.Predicate<ru.minecomplex.mac.collision.Aabb> othersNear, java.util.function.Consumer<Boolean> riderMoves) {
        this.othersNear = othersNear;
        this.riderMoves = riderMoves;
        vehiclePosition = () -> null;
        riderMoves.accept(false);
        Set<Unmodeled> gaps = EnumSet.noneOf(Unmodeled.class);
        riderSimulated = false;
        simulatedBody = null;
        riderSimulatedCheck = null;
        rideVelocity = null;
        rideVelocityEstimate = null;
        rideTransform = null;
        CompensatedEntities entities = level.entities();
        if (entities == null)
            return skip(gaps, "транспорт: сущностей ещё нет", null);
        gaps.addAll(entities.unmodeled());
        PassengerGraph graph = entities.passengers();
        int me = entities.localPlayerId();
        Integer rootId = graph.root(me);
        if (rootId == null) {
            gaps.add(Unmodeled.RIDING);
            return skip(gaps, "граф пассажиров не разрешён", null);
        }
        var own = graph.get(me);
        var root = graph.get(rootId);
        var spawn = entities.spawnInput(rootId);
        String type = spawn == null ? null : Packets.type(spawn);
        String name = type == null ? "транспорт" : type.substring(type.indexOf(':') + 1);
        if (!Objects.equals(own.vehicle(), rootId)) {
            gaps.add(Unmodeled.RIDING);
            return skip(gaps, name + ": вложенная посадка не симулируется", name);
        }
        if (type == null || types.type(type) == null) {
            gaps.add(Unmodeled.RIDING);
            return skip(gaps, "транспорт без известного типа", name);
        }
        List<ServerboundMoveVehiclePacket> sent = new ArrayList<>();
        ServerboundPaddleBoatPacket paddles = null;
        for (Object packet : frame.packets()) {
            if (packet instanceof ServerboundMoveVehiclePacket move)
                sent.add(move);
            if (packet instanceof ServerboundPaddleBoatPacket paddle)
                paddles = paddle;
        }
        if (boat(type))
            return boat(frame, env, entities, rootId, root, type, name, sent, paddles, p, local, data, gaps);
        HorseState.Kind kind = horseKind(type);
        if (kind != null && types.rideable(type) != null) {
            return horse(frame, env, entities, rootId, root, type, name, kind, sent, p, local, data, gaps, level.gameTime());
        }
        OtherVehicleRiding.Kind living = livingKind(type);
        if (living != null && types.rideable(type) != null) {
            return living(frame, env, entities, rootId, root, type, name, living, sent, p, local, data, gaps);
        }
        following = null;
        if (serverDriven(type)) {
            // no rider steers it: the client only interpolates what the server sends and sends no vehicle movement
            return skip(gaps, sent.isEmpty() ? name + ": транспорт ведёт сервер" : name + ": транспорт ведёт сервер, но клиент отправил позицию транспорта", name);
        }
        gaps.add(Unmodeled.RIDING);
        return skip(gaps, name + ": езда на этом транспорте пока не симулируется", name);
    }

    /**
     * Every boat and raft type (chest variants included): {@code AbstractBoat} subclasses.
     */
    static boolean boat(String type) {
        return type.endsWith("_boat") || type.endsWith("_raft");
    }

    private Outcome boat(ClientTickFrame frame, MovementEnvironment env, CompensatedEntities entities, int rootId, PassengerGraph.View root, String type, String name, List<ServerboundMoveVehiclePacket> sent, ServerboundPaddleBoatPacket paddles, PlayerState p, LocalPlayerState local, ClientPlayerData data, Set<Unmodeled> gaps) {
        int me = entities.localPlayerId();
        // AbstractBoat.getControllingPassenger is the first passenger (a Player in 26.3, any LivingEntity before): the
        // local player drives only from the front seat
        if (root.passengers().getFirst() != me) {
            following = null;
            return skip(gaps, sent.isEmpty() ? name + ": управляет другой пассажир" : name + ": управляет другой пассажир, но клиент отправил позицию транспорта", name);
        }
        boolean raft = type.endsWith("_raft");
        boolean chest = type.contains("_chest_");
        var dimensions = types.type(type).dimensions();
        Function<PlayerState, Vec3d> attachment = rider -> types.playerDimensions(rider.pose, rider.dimensionScale).attachments().get(EntityAttachment.VEHICLE, 0, rider.yRot);
        BoatState state = entities.boatState(rootId);
        boolean seeding = following != root.generation() || state == null || state.generation != root.generation();
        if (seeding) {
            if (sent.isEmpty())
                return skip(gaps, name + ": посадка, ждём позицию транспорта от клиента", name);
            BoatState seeded = new BoatState(root.generation(), dimensions.width(), dimensions.height(), dimensions.eyeHeight());
            if (state != null && state.generation == root.generation()) {
                // the boat's own fields outlive a ride; only 26.3 clears the rotation speed while nobody here drives
                seeded.input = state.input;
                seeded.carriedUncertain = state.carriedUncertain;
            }
            seeded.body.firstTick = false;
            seeded.body.fluidShapes = p.fluidShapes;
            // the client's boat before this tick (an interpolation still running: it then reports the target), else the
            // transform it reports after the tick
            interpolationDiverged = false;
            var start = entities.motions() == null ? null : entities.motions().rideStart(rootId);
            seedTransformKnown = start != null && !entities.motions().rideStartUncertain(rootId);
            if (start != null) {
                placeAt(seeded, start.body.position, start.body.yRot, start.body.xRot, start.body.onGround);
                if (start.interpolating())
                    seeded.interpolation = start.interpolation.boatCopy();
            } else {
                place(seeded, sent.getLast());
            }
            seedVelocityUnknown = entities.motions() != null && entities.motions().rideStartUncertain(rootId) || seeded.carriedUncertain;
            lastReportedVehicle = null;
            // the status the boat had on its last (remote) tick: AbstractBoat.tick takes it before that tick's interpolation
            // step, where the tick began (26.3: after Entity.commonTick's step, where it ended)
            Vec3d statusAt = seeded.body.position;
            var statusBox = seeded.body.boundingBox().move(statusAt.x() - seeded.body.x(), statusAt.y() - seeded.body.y(), statusAt.z() - seeded.body.z());
            seeded.status = MovementTickerBoat.status(env, seeded, statusBox, gaps);
            state = seeded;
            entities.boatState(rootId, state);
        }
        state.noGravity = noGravity(entities, rootId);
        // ClientPacketListener.handleSetEntityMotion replaces the driven boat's delta movement
        // before floatBoat/controlBoat, including a server reset just after mounting.
        if (entities.motions() != null) {
            Vec3d motion = entities.motions().takeRideVelocity(rootId);
            if (motion != null)
                state.body.deltaMovement = motion;
        }
        boolean divergedBefore = !seeding && interpolationDiverged;
        boolean corrected = false;
        if (!seeding && sent.size() > 1) {
            // a server vehicle_move was answered before the tick with the snapped position
            place(state, sent.get(sent.size() - 2));
            state.interpolation.cancel();
            corrected = true;
        }
        BoatState boatNow = state;
        vehiclePosition = () -> boatNow.body.position;
        // AbstractBoat.tick pushes the entities in its inflated box (pushes and collision rules are not modelled)
        var simulation = new BoatPlayerTick(env, entities, rootId, p, local, data, raft, chest, attachment, box -> {
            if (othersNear.test(box))
                gaps.add(Unmodeled.VEHICLE_ENTITIES);
            return List.of();
        });
        simulation.beforeRider = boat -> riderMoves.accept(true);
        if (frame.movement() != null && frame.movement().hasRotation()) {
            // the rotation 26.3 sends is the one positionRider left; the rider's own tick used the yaw before it
            float endYaw = p.yRot;
            boolean[] known = { true };
            simulation.beforeRider = boat -> {
                riderMoves.accept(true);
                Float start = ru.minecomplex.mac.engine.movementtick.BoatPassengerPhysics.riderYawBeforeSeat(endYaw, boat.deltaRotation, boat.body.yRot);
                // not certain: the boat is still exact, only the rider's own velocity (carried by a dismount) is not
                if (start == null)
                    known[0] = false;
                p.yRot = start == null ? endYaw - boat.deltaRotation : start;
            };
            riderSimulatedCheck = known;
        }
        TickPackets result = simulation.tick(PlayerInput.fromMask(frame.input() & 127), Tri.TRUE, true, true);
        riderMoves.accept(false);
        gaps.addAll(result.unmodeled());
        riderSimulated = riderSimulatedCheck == null || riderSimulatedCheck[0];
        riderSimulatedCheck = null;
        ServerboundMoveVehiclePacket predicted = null;
        ServerboundPaddleBoatPacket predictedPaddles = null;
        for (Object packet : result.packets()) {
            if (packet instanceof ServerboundMoveVehiclePacket move)
                predicted = move;
            if (packet instanceof ServerboundPaddleBoatPacket paddle)
                predictedPaddles = paddle;
        }
        ServerboundMoveVehiclePacket reported = sent.isEmpty() ? null : sent.getLast();
        if (reported != null) {
            // continue from the client's vehicle, the passenger on its seat; a boat still interpolating reports the target
            // and keeps its own transform (a target other than the simulated one leaves it unknown until a report)
            if (!state.interpolation.active()) {
                place(state, reported);
                interpolationDiverged = false;
            } else if (!Packets.same(reported, predicted)) {
                interpolationDiverged = true;
            }
            reseat(state, p, local, root, me, raft, chest, attachment.apply(p));
        }
        following = root.generation();
        List<String> startNotes = new ArrayList<>();
        // a boat at rest with the client's yaw: what it keeps between rides is the client's again
        startUncertainty(gaps, startNotes, predicted, reported == null || predicted == null || reported.movingTo().yRot() == predicted.movingTo().yRot() ? reported : null, seedTransformKnown && !state.interpolation.active() && !interpolationDiverged);
        state.carriedUncertain = seedVelocityUnknown;
        rideVelocity = state.body.deltaMovement;
        simulatedBody = seedVelocityUnknown ? null : state.body;
        rideTransform = new RideTransform(state.body.position, state.body.yRot, state.body.xRot, ru.minecomplex.mac.entity.interpolation.EntityInterpolation.linear(state.interpolation), !interpolationDiverged && !seedVelocityUnknown, state.body.onGround);
        if (seeding)
            return skip(gaps, name + ": посадка, начальная позиция транспорта взята у клиента", name);
        List<String> notes = new ArrayList<>(startNotes);
        divergedInterpolation(divergedBefore, gaps, notes);
        if (corrected)
            notes.add("коррекция позиции сервером");
        if (paddles != null && predictedPaddles != null && !Packets.same(paddles, predictedPaddles)) {
            notes.add("вёсла: пред " + paddleText(predictedPaddles) + ", клиент " + paddleText(paddles));
        }
        Vec3d predictedPosition = predicted == null ? state.body.position : new Vec3d(predicted.movingTo().position().x(), predicted.movingTo().position().y(), predicted.movingTo().position().z());
        if (reported == null) {
            notes.add(predicted == null ? "клиент не отправил позицию транспорта" : "клиент не отправил позицию транспорта, а должен был");
            return new Outcome(MovementCheckRunner.Status.COMPARED, 0.0, predictedPosition, null, gaps, String.join("; ", notes), name);
        }
        if (predicted == null)
            notes.add("движок не отправил бы позицию транспорта");
        Vec3d reportedPosition = new Vec3d(reported.movingTo().position().x(), reported.movingTo().position().y(), reported.movingTo().position().z());
        return new Outcome(MovementCheckRunner.Status.COMPARED, distance(predictedPosition, reportedPosition), predictedPosition, reportedPosition, gaps, String.join("; ", notes), name);
    }

    /**
     * Rideable types no player steers: minecarts (the server moves them, {@code AbstractMinecart} has no controlling
     * passenger) and llamas (never saddled: not saddleable before 1.21.5, outside {@code #can_equip_saddle} after).
     */
    static boolean serverDriven(String type) {
        return type.endsWith("minecart") || type.equals("minecraft:llama") || type.equals("minecraft:trader_llama");
    }

    /**
     * The horse-family kinds with a whole-tick engine.
     */
    static HorseState.Kind horseKind(String type) {
        return switch(type) {
            case "minecraft:horse" ->
                HorseState.Kind.HORSE;
            case "minecraft:donkey" ->
                HorseState.Kind.DONKEY;
            case "minecraft:mule" ->
                HorseState.Kind.MULE;
            case "minecraft:skeleton_horse" ->
                HorseState.Kind.SKELETON_HORSE;
            case "minecraft:zombie_horse" ->
                HorseState.Kind.ZOMBIE_HORSE;
            // CamelHusk overrides nothing a player's ride reads (only mob riders, sounds, breeding): a camel with its own size
            case "minecraft:camel", "minecraft:camel_husk" ->
                HorseState.Kind.CAMEL;
            default ->
                null;
        };
    }

    /**
     * The other living mounts with a whole-tick engine.
     */
    static OtherVehicleRiding.Kind livingKind(String type) {
        return switch(type) {
            case "minecraft:pig" ->
                OtherVehicleRiding.Kind.PIG;
            case "minecraft:strider" ->
                OtherVehicleRiding.Kind.STRIDER;
            case "minecraft:happy_ghast" ->
                OtherVehicleRiding.Kind.HAPPY_GHAST;
            case "minecraft:nautilus" ->
                OtherVehicleRiding.Kind.NAUTILUS;
            case "minecraft:zombie_nautilus" ->
                OtherVehicleRiding.Kind.ZOMBIE_NAUTILUS;
            default ->
                null;
        };
    }

    private Outcome living(ClientTickFrame frame, MovementEnvironment env, CompensatedEntities entities, int rootId, PassengerGraph.View root, String type, String name, OtherVehicleRiding.Kind kind, List<ServerboundMoveVehiclePacket> sent, PlayerState p, LocalPlayerState local, ClientPlayerData data, Set<Unmodeled> gaps) {
        int me = entities.localPlayerId();
        var rideable = types.rideable(type);
        var mob = new MobData(entities, rootId);
        List<String> notes = new ArrayList<>();
        if (root.passengers().getFirst() != me) {
            following = null;
            return skip(gaps, name + ": управляет другой пассажир", name);
        }
        Tri controls = controllerForTick(entities, rootId, data, frame, data.clientLoaded);
        if (controls == Tri.FALSE) {
            if (following == root.generation())
                inactiveFollowing = following;
            following = null;
            return skip(gaps, sent.isEmpty() ? name + ": не управляется игроком, ведёт сервер" : name + ": не управляется игроком, но клиент отправил позицию транспорта", name);
        }
        if (controls == Tri.EITHER) {
            gaps.add(Unmodeled.RIDING);
            notes.add("управление не определено");
        }
        boolean baby = mob.baby();
        boolean nautilus = kind == OtherVehicleRiding.Kind.NAUTILUS || kind == OtherVehicleRiding.Kind.ZOMBIE_NAUTILUS;
        boolean steered = kind == OtherVehicleRiding.Kind.PIG || kind == OtherVehicleRiding.Kind.STRIDER;
        String prefix = kind == OtherVehicleRiding.Kind.PIG ? "Pig." : "Strider.";
        LivingVehicleState state = entities.livingState(rootId);
        boolean seeding = following != root.generation() || state == null || state.generation != root.generation();
        if (seeding && state != null && state.generation == root.generation() && inactiveFollowing == root.generation()) {
            // ItemBasedSteering and dash/animation clocks belong to the entity, not to holding the steering item.
            var after = sent.isEmpty() ? ru.minecomplex.mac.packet.Packets.vehicleMove(new net.minecraft.world.phys.Vec3(state.body.x(), state.body.y(), state.body.z()), state.body.yRot, state.body.xRot, state.body.onGround) : sent.getLast();
            seedTransform(entities, rootId, state.body, after);
            state.body.deltaMovement = startVelocity(entities, rootId, after);
            seeding = false;
        }
        inactiveFollowing = null;
        var snapshots = entities.attributes(rootId);
        if (seeding) {
            if (sent.isEmpty())
                return skip(gaps, name + ": посадка, ждём позицию транспорта от клиента", name);
            var body = new PlayerState();
            body.fixedBodyDimensions = true;
            body.firstTick = false;
            body.fluidShapes = p.fluidShapes;
            body.attributes = attributes(type, snapshots);
            appliedAttributes = snapshots;
            state = new LivingVehicleState(root.generation(), kind, body);
            var s = state.control;
            seedTransform(entities, rootId, body, sent.getLast());
            body.deltaMovement = startVelocity(entities, rootId, sent.getLast());
            s.yaw = s.yawOld = s.headYaw = s.bodyYaw = body.yRot;
            s.pitch = body.xRot;
            if (steered) {
                appliedBoost = entities.assigned(rootId, mob.id(prefix + "DATA_BOOST_TIME"));
                var boost = entities.motions() == null ? null : entities.motions().steeringBoost(rootId);
                if (boost != null) {
                    s.boosting = boost.boosting;
                    s.boostTime = boost.boostTime;
                    s.boostDuration = boost.boostDuration;
                    boostUnknownTicks = 0;
                } else
                    boostUnknownTicks = appliedBoost != 0 ? Integer.MAX_VALUE : 0;
            }
            if (kind == OtherVehicleRiding.Kind.STRIDER) {
                appliedSuffocating = entities.assigned(rootId, mob.id("Strider.DATA_SUFFOCATING"));
                state.suffocating = mob.flag("Strider.DATA_SUFFOCATING");
            }
            if (nautilus) {
                appliedNautilusDash = entities.assigned(rootId, mob.id("AbstractNautilus.DASH"));
                s.dashing = mob.flag("AbstractNautilus.DASH");
                // a dash before this ride: its cooldown (40 ticks) is not known
                dashUnknownTicks = s.dashing ? 40 : 0;
            }
            entities.livingState(rootId, state);
        } else {
            var s = state.control;
            rideMotion(entities, rootId, state.body);
            if (steered && entities.assigned(rootId, mob.id(prefix + "DATA_BOOST_TIME")) != appliedBoost) {
                appliedBoost = entities.assigned(rootId, mob.id(prefix + "DATA_BOOST_TIME"));
                // ItemBasedSteering.onSynced restarts the clock; boostFactor reads the synced total
                new OtherVehicleRiding(kind).boostSynced(s, mob.integer(prefix + "DATA_BOOST_TIME"));
                boostUnknownTicks = 0;
            }
            if (kind == OtherVehicleRiding.Kind.STRIDER && entities.assigned(rootId, mob.id("Strider.DATA_SUFFOCATING")) != appliedSuffocating) {
                appliedSuffocating = entities.assigned(rootId, mob.id("Strider.DATA_SUFFOCATING"));
                state.suffocating = mob.flag("Strider.DATA_SUFFOCATING");
            }
            if (nautilus && entities.assigned(rootId, mob.id("AbstractNautilus.DASH")) != appliedNautilusDash) {
                appliedNautilusDash = entities.assigned(rootId, mob.id("AbstractNautilus.DASH"));
                s.dashing = mob.flag("AbstractNautilus.DASH");
                new OtherVehicleRiding(kind).dashSynced(s, false);
            }
            for (var entry : snapshots.entrySet()) {
                if (!entry.getValue().equals(appliedAttributes.get(entry.getKey())))
                    state.body.attributes.apply(entry.getValue());
            }
            appliedAttributes = snapshots;
        }
        if (boostUnknownTicks > 0) {
            // An unsimulated controller phase cannot be repaired by counting a guessed server boost duration.
            if (boostUnknownTicks != Integer.MAX_VALUE)
                boostUnknownTicks--;
            gaps.add(Unmodeled.RIDING);
            notes.add("ускорение, начатое до посадки, не известно");
        }
        if (dashUnknownTicks > 0) {
            dashUnknownTicks--;
            gaps.add(Unmodeled.RIDING);
            notes.add("рывок, начатый до посадки, не известен");
        }
        divergedInterpolation(!seeding && interpolationDiverged, gaps, notes);
        final LivingVehicleState vehicle = state;
        PlayerState body = vehicle.body;
        vehiclePosition = () -> body.position;
        float scale = rideable.scale(body.attributes.value(Attribute.SCALE));
        boolean exceptional = !entities.effects(rootId).isEmpty() || !mob.alive();
        if (!entities.effects(rootId).isEmpty())
            notes.add("эффекты на транспорте");
        var dimensions = types.livingDimensions(rideable, baby, Pose.STANDING, scale);
        body.bodyWidth = dimensions.width();
        body.bodyHeight = dimensions.height();
        // Test the finite selection-before-handlers alternative against the complete vanilla packet, without
        // replacing a reported position into its seed. Both candidates run the same root physics.
        var early = entities.motions() == null ? null : entities.motions().earlyControllerStart(rootId);
        if (early != null && !sent.isEmpty()) {
            var alternate = vehicle.copy();
            alternate.body.position = early.body.position;
            alternate.body.yRot = early.body.yRot;
            alternate.body.xRot = early.body.xRot;
            alternate.body.onGround = early.body.onGround;
            alternate.body.interpolation = early.interpolation.copy();
            var reportedPacket = sent.getLast();
            var ordinaryPacket = previewLivingPacket(vehicle.copy(), env, rideable, dimensions.eyeHeight(), mob, p);
            var earlyPacket = previewLivingPacket(alternate, env, rideable, dimensions.eyeHeight(), mob, p);
            if (Packets.same(reportedPacket, earlyPacket) && !Packets.same(reportedPacket, ordinaryPacket)) {
                entities.motions().acceptEarlyController(rootId);
                body.position = early.body.position;
                body.yRot = early.body.yRot;
                body.xRot = early.body.xRot;
                body.onGround = early.body.onGround;
                body.interpolation = early.interpolation.copy();
            } else if (!Packets.same(reportedPacket, ordinaryPacket) || Packets.same(reportedPacket, earlyPacket)) {
                // A tie cannot certify hidden interpolation/velocity; no matching order is uncertain as well.
                gaps.add(Unmodeled.RIDING);
                notes.add("порядок выбора предмета и обработки пакетов транспорта не определён");
                seedVelocityUnknown = true;
            }
            vehiclePosition = () -> body.position;
        }
        var bodyPhysics = new LivingVehicleBodyPhysics(env, body, kind, dimensions.eyeHeight(), rideable.pushedByFluid(), true, true, mob.noGravity(), body.attributes.value(Attribute.FLYING_SPEED));
        var vehicleTick = new LivingVehicleTickPhysics(vehicle, bodyPhysics);
        boolean saddled = nautilus && controls != Tri.FALSE;
        var tickFacts = new LivingVehicleTickPhysics.Facts(true, saddled, !mob.noAi(), mob.noAi(), baby, rideable.maxHeadYRot(), !othersNear.test(pushReach(body)), exceptional, rideable.canFloat());
        Function<PlayerState, Vec3d> attachment = rider -> types.playerDimensions(rider.pose, rider.dimensionScale).attachments().get(EntityAttachment.VEHICLE, 0, rider.yRot);
        var ride = new LivingVehiclePlayerRide(entities.passengers(), me, rootId, vehicle, vehicleTick.control(), () -> vehicleTick.controlFacts(tickFacts), index -> types.livingDimensions(rideable, baby, Pose.STANDING, scale).attachments().getClamped(ru.minecomplex.mac.entity.EntityAttachment.PASSENGER, index, body.yRot), () -> scale * rideable.ageScale(baby), () -> mountPacket(vehicle.generation, body), attachment);
        var simulation = new LivingVehiclePlayerTick(env, vehicle, vehicleTick, ride, p, local, data);
        gaps.addAll(simulation.simulateVehicleTick(tickFacts));
        if (steered && entities.motions() != null)
            entities.motions().steeringBoost(rootId, vehicle.control, boostUnknownTicks == 0 && Boolean.TRUE.equals(entities.motions().ticking(rootId)) && frame.movement() != null && Packets.kind(frame.movement()) == MoveKind.ROT);
        // LocalPlayer reads its vehicle after the vehicle's tick (the ride): the nautilus is jumpable while saddled
        riderMoves.accept(true);
        TickPackets result = simulation.simulatePassengerTick(PlayerInput.fromMask(frame.input() & 127));
        riderMoves.accept(false);
        riderSimulated = true;
        List<Object> packets = new ArrayList<>(result.packets());
        gaps.addAll(result.unmodeled());
        {
            var late = simulation.sendAfterEntityTicks();
            packets.addAll(late.packets());
            gaps.addAll(late.unmodeled());
        }
        ServerboundMoveVehiclePacket predicted = null;
        for (Object packet : packets) {
            if (packet instanceof ServerboundMoveVehiclePacket move)
                predicted = move;
        }
        ServerboundMoveVehiclePacket reported = sent.isEmpty() ? null : sent.getLast();
        if (reported != null) {
            follow(body, predicted, reported);
            vehicle.resolvedBaseline();
            ride.positionRider(p, local);
        }
        following = root.generation();
        startUncertainty(gaps, notes, predicted, reported, seedTransformKnown && (body.interpolation == null || !body.interpolation.active()) && !interpolationDiverged);
        // a ride that started from an assumed velocity leaves an unknown one until the mount rests
        rideVelocity = seedVelocityUnknown ? null : body.deltaMovement;
        rideVelocityEstimate = body.deltaMovement;
        simulatedBody = seedVelocityUnknown ? null : body;
        rideTransform = new RideTransform(body.position, body.yRot, body.xRot, body.interpolation, !seedVelocityUnknown && !interpolationDiverged, body.onGround);
        if (seeding)
            return skip(gaps, name + ": посадка, начальная позиция транспорта взята у клиента", name);
        Vec3d predictedPosition = predicted == null ? body.position : new Vec3d(predicted.movingTo().position().x(), predicted.movingTo().position().y(), predicted.movingTo().position().z());
        if (reported == null) {
            notes.add(predicted == null ? "клиент не отправил позицию транспорта" : "клиент не отправил позицию транспорта, а должен был");
            return new Outcome(MovementCheckRunner.Status.COMPARED, 0.0, predictedPosition, null, gaps, String.join("; ", notes), name);
        }
        if (predicted == null)
            notes.add("движок не отправил бы позицию транспорта");
        Vec3d reportedPosition = new Vec3d(reported.movingTo().position().x(), reported.movingTo().position().y(), reported.movingTo().position().z());
        return new Outcome(MovementCheckRunner.Status.COMPARED, distance(predictedPosition, reportedPosition), predictedPosition, reportedPosition, gaps, String.join("; ", notes), name);
    }

    /**
     * Who drives a pig/strider (saddled, the first passenger a player holding the steering item: {@code Pig/Strider
     * .getControllingPassenger}), a happy ghast (wearing a harness, not on its still timeout) or a nautilus (saddled). The
     * saddle: before 1.21.5 the mob's data flag; after it a {@code SADDLE} stack that is equippable there, on a mob that
     * {@code canUseSlot(SADDLE)} (alive, adult; a nautilus also tame).
     */
    private Tri livingController(OtherVehicleRiding.Kind kind, CompensatedEntities entities, int id, MobData mob, ClientPlayerData data) {
        boolean adult = mob.alive() && !mob.baby();
        return switch(kind) {
            case PIG, STRIDER ->
                {
                    String prefix = kind == OtherVehicleRiding.Kind.PIG ? "Pig." : "Strider.";
                    Tri saddle = equipped(entities.saddle(id), adult, "SADDLE", "minecraft:saddle");
                    yield and(saddle, holding(data, kind == OtherVehicleRiding.Kind.PIG ? "minecraft:carrot_on_a_stick" : "minecraft:warped_fungus_on_a_stick"));
                }
            case HAPPY_GHAST ->
                mob.flag("HappyGhast.STAYS_STILL") ? Tri.FALSE : equipped(entities.body(id), adult, "BODY", null);
            case NAUTILUS, ZOMBIE_NAUTILUS ->
                equipped(entities.saddle(id), adult && mob.tame(), "SADDLE", "minecraft:saddle");
        };
    }

    /**
     * {@code hasValidEquippableItemForSlot}: a stack whose {@code equippable} slot is {@code slot} on a mob that may use the
     * slot; that the stack may equip this type is resolved only for the vanilla item ({@code vanillaItem}, or any harness
     * for the body slot) without an equippable patch.
     */
    private Tri equipped(ItemStack stack, boolean canUseSlot, String slot, String vanillaItem) {
        if (stack == null || !canUseSlot)
            return Tri.FALSE;
        if (!slot.equals(ItemMechanics.equippableSlot(stack)))
            return Tri.FALSE;
        boolean vanilla = vanillaItem == null ? Stacks.key(stack).endsWith("_harness") : Stacks.key(stack).equals(vanillaItem);
        boolean unpatched = (!stack.getComponentsPatch().map.containsKey(DataComponents.EQUIPPABLE));
        return vanilla && unpatched ? Tri.TRUE : Tri.EITHER;
    }

    /**
     * {@code Player.isHolding(item)}: the selected hotbar stack or the offhand, as far as the client's inventory is known.
     */
    private static Tri holding(ClientPlayerData data, String item) {
        var inventory = data.inventory;
        int main = inventory.selected();
        boolean known = false, unknown = false;
        for (int slot : new int[] { main, ru.minecomplex.mac.replica.CompensatedInventory.OFFHAND }) {
            if (inventory.unknown(slot))
                unknown = true;
            else if (Stacks.key(inventory.item(slot)).equals(item))
                known = true;
        }
        return known ? Tri.TRUE : unknown ? Tri.EITHER : Tri.FALSE;
    }

    private static Tri and(Tri a, Tri b) {
        if (a == Tri.FALSE || b == Tri.FALSE)
            return Tri.FALSE;
        return a == Tri.TRUE && b == Tri.TRUE ? Tri.TRUE : Tri.EITHER;
    }

    /**
     * The same controller predicate drives wire handlers and the ridden travel dispatch.
     */
    Tri controller(CompensatedEntities entities, int id, ClientPlayerData player) {
        var spawn = entities.spawnInput(id);
        if (spawn == null)
            return Tri.EITHER;
        String type = Packets.type(spawn);
        var mob = new MobData(entities, id);
        if (boat(type))
            return Tri.TRUE;
        if (horseKind(type) != null)
            return saddled(entities, id, type, mob);
        var kind = livingKind(type);
        if (kind != null)
            return livingController(kind, entities, id, mob, player);
        return serverDriven(type) ? Tri.FALSE : Tri.EITHER;
    }

    /**
     * Root-only dry run: the passenger phase positions the rider but does not move a pig/strider root.
     */
    private ServerboundMoveVehiclePacket previewLivingPacket(LivingVehicleState candidate, MovementEnvironment env, EntityTypes.Rideable rideable, float eyeHeight, MobData mob, PlayerState rider) {
        var body = candidate.body;
        vehiclePosition = () -> body.position;
        var solver = new LivingVehicleBodyPhysics(env, body, candidate.kind, eyeHeight, rideable.pushedByFluid(), true, true, mob.noGravity(), body.attributes.value(Attribute.FLYING_SPEED));
        var tick = new LivingVehicleTickPhysics(candidate, solver);
        tick.tick(new LivingVehicleTickPhysics.Facts(true, false, !mob.noAi(), mob.noAi(), mob.baby(), rideable.maxHeadYRot(), !othersNear.test(pushReach(body)), !mob.alive(), rideable.canFloat()), new OtherVehicleRiding.Rider(rider.xxa, rider.zza, rider.yRot, rider.xRot, rider.jumping));
        return mountPacket(candidate.generation, body).packet();
    }

    /**
     * gameMode.tick announces the previous selection before handleKeybinds can change it again. The riding send always
     * includes move_vehicle iff the root is locally authoritative. For item-steered mounts this resolves the current
     * controller without pretending the unannounced hotbar slot is known to the inventory model.
     */
    Tri controllerForTick(CompensatedEntities entities, int id, ClientPlayerData data, ClientTickFrame frame, boolean loaded) {
        var spawn = entities.spawnInput(id);
        if (spawn == null || !loaded || frame.movement() == null || Packets.kind(frame.movement()) != MoveKind.ROT)
            return controller(entities, id, data);
        var kind = livingKind(Packets.type(spawn));
        if (kind != OtherVehicleRiding.Kind.PIG && kind != OtherVehicleRiding.Kind.STRIDER)
            return controller(entities, id, data);
        var mob = new MobData(entities, id);
        boolean adult = mob.alive() && !mob.baby();
        String prefix = kind == OtherVehicleRiding.Kind.PIG ? "Pig." : "Strider.";
        Tri saddle = equipped(entities.saddle(id), adult, "SADDLE", "minecraft:saddle");
        boolean sent = false, rotation = false;
        for (var packet : frame.packets()) {
            if (packet instanceof ServerboundMovePlayerPacket move && Packets.kind(move) == MoveKind.ROT)
                rotation = true;
            // A move_vehicle before the rider's Rot may be the immediate reply to a server correction.
            if (rotation && packet instanceof ServerboundMoveVehiclePacket)
                sent = true;
        }
        if (!sent)
            return Tri.FALSE;
        String item = kind == OtherVehicleRiding.Kind.PIG ? "minecraft:carrot_on_a_stick" : "minecraft:warped_fungus_on_a_stick";
        boolean possible = false;
        for (int slot : new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, ru.minecomplex.mac.replica.CompensatedInventory.OFFHAND }) {
            possible |= data.inventory.unknown(slot) || Stacks.key(data.inventory.item(slot)).equals(item);
        }
        // The packet proves the controller, but cannot supply a missing saddle or invent a steering item.
        return possible ? saddle : Tri.FALSE;
    }

    /**
     * A passenger ticks even when this client does not steer the root (notably a pig without its steering item).
     */
    void tickUncontrolledPassenger(ClientTickFrame frame, CompensatedWorld level, MovementEnvironment env, PlayerState p, LocalPlayerState local, ClientPlayerData data, Set<Unmodeled> gaps, java.util.function.Consumer<Boolean> riderMoves) {
        var entities = level.entities();
        if (entities == null) {
            gaps.add(Unmodeled.RIDING);
            return;
        }
        Integer id = entities.passengers().root(entities.localPlayerId());
        if (id == null) {
            gaps.add(Unmodeled.RIDING);
            return;
        }
        var root = entities.passengers().get(id);
        if (root == null) {
            gaps.add(Unmodeled.RIDING);
            return;
        }
        var spawn = entities.spawnInput(id);
        var mob = spawn == null ? null : new MobData(entities, id);
        var rideable = spawn == null ? null : types.rideable(Packets.type(spawn));
        var motion = entities.motions() == null ? null : entities.motions().remotePassengerVehicle(id);
        var own = entities.passengers().get(entities.localPlayerId());
        Tri authority = root.passengers().isEmpty() || root.passengers().getFirst() != entities.localPlayerId() ? Tri.FALSE : controllerForTick(entities, id, data, frame, data.clientLoaded);
        boolean resolved = motion != null && rideable != null && livingKind(Packets.type(spawn)) != null && livingKind(Packets.type(spawn)) != OtherVehicleRiding.Kind.STRIDER && horseKind(Packets.type(spawn)) == null && own != null && Objects.equals(own.vehicle(), id) && authority == Tri.FALSE;
        // Strider walk bob and horse/Camel animation while remotely controlled need their own remote clocks.
        if (!resolved)
            gaps.add(Unmodeled.RIDING);
        var context = new ru.minecomplex.mac.engine.movementtick.LocalPlayerRide() {

            public ru.minecomplex.mac.mechanics.fluid.FluidWorld.BoatPassenger boatFluidFacts() {
                return null;
            }

            public boolean nonBoatVehicleResolved() {
                return rideable != null;
            }

            public VehiclePacketState rootVehicle() {
                return new VehiclePacketState(root.generation(), authority, p.position, p.yRot, p.xRot, null, false, false);
            }

            public Set<Unmodeled> unmodeled() {
                return resolved ? Set.of() : Set.of(Unmodeled.RIDING);
            }

            public boolean controlsBoat() {
                return false;
            }

            public void setBoatInput(PlayerInput input) {
                throw new IllegalStateException("Uncontrolled vehicle");
            }

            public void positionRider(PlayerState player, LocalPlayerState own) {
                if (!resolved)
                    return;
                float scale = rideable.scale(attributes(Packets.type(spawn), entities.attributes(id)).value(Attribute.SCALE));
                var dimensions = types.livingDimensions(rideable, mob.baby(), mob.pose(), scale);
                var point = dimensions.attachments().getClamped(EntityAttachment.PASSENGER, root.passengers().indexOf(entities.localPlayerId()), motion.body.yRot);
                var attachment = types.playerDimensions(player.pose, player.dimensionScale).attachments().get(EntityAttachment.VEHICLE, 0, player.yRot);
                var riding = motion.body.position.add(point);
                player.position = ru.minecomplex.mac.util.math.VanillaMath.position(player.position, new Vec3d(riding.x() - attachment.x(), riding.y() - attachment.y(), riding.z() - attachment.z()));
            }
        };
        p.oldPosition = p.position;
        local.yawOld = p.yRot;
        var physics = new MovementTickerPlayer(env, p, local, data);
        riderMoves.accept(true);
        var result = physics.simulateRideTick(PlayerInput.fromMask(frame.input() & 127), context);
        gaps.addAll(result);
        gaps.addAll(physics.sendAfterEntityTicks(context));
        riderMoves.accept(false);
        riderSimulated = resolved && result.isEmpty();
    }

    private Outcome horse(ClientTickFrame frame, MovementEnvironment env, CompensatedEntities entities, int rootId, PassengerGraph.View root, String type, String name, HorseState.Kind kind, List<ServerboundMoveVehiclePacket> sent, PlayerState p, LocalPlayerState local, ClientPlayerData playerData, Set<Unmodeled> gaps, Long gameTime) {
        int me = entities.localPlayerId();
        var rideable = types.rideable(type);
        var data = new MobData(entities, rootId);
        List<String> notes = new ArrayList<>();
        // AbstractHorse.getControllingPassenger: a saddled horse is driven by its first passenger if that is a player
        if (root.passengers().getFirst() != me) {
            following = null;
            return skip(gaps, name + ": управляет другой пассажир", name);
        }
        Tri saddled = saddled(entities, rootId, type, data);
        if (saddled == Tri.FALSE) {
            following = null;
            if (sent.isEmpty())
                return unsaddledHorse(frame, env, entities, rootId, root, kind, p, local, playerData, gaps, gameTime, name, data, rideable);
            return skip(gaps, sent.isEmpty() ? name + ": без седла, ведёт сервер" : name + ": без седла, но клиент отправил позицию транспорта", name);
        }
        if (saddled == Tri.EITHER) {
            gaps.add(Unmodeled.RIDING);
            notes.add("седло не определено");
        }
        boolean camel = kind == HorseState.Kind.CAMEL;
        long time = gameTime == null ? 0L : gameTime;
        if (camel && gameTime == null) {
            gaps.add(Unmodeled.RIDING);
            notes.add("время мира неизвестно");
        }
        boolean baby = data.baby();
        HorseState state = entities.horseState(rootId);
        boolean seeding = following != root.generation() || state == null || state.generation != root.generation();
        var snapshots = entities.attributes(rootId);
        if (seeding) {
            if (sent.isEmpty())
                return skip(gaps, name + ": посадка, ждём позицию транспорта от клиента", name);
            var body = new PlayerState();
            body.fixedBodyDimensions = true;
            body.firstTick = false;
            body.fluidShapes = p.fluidShapes;
            body.attributes = attributes(type, snapshots);
            appliedAttributes = snapshots;
            state = new HorseState(root.generation(), kind, body);
            state.standing = data.horseFlag(32);
            state.eating = data.horseFlag(16);
            if (camel) {
                state.dashing = data.camelDash();
                state.lastPoseChangeTick = data.camelLastPoseChangeTick();
                state.pose = camelPose(data.pose());
            }
            appliedFlags = entities.assigned(rootId, data.horseFlagsId());
            appliedDash = entities.assigned(rootId, data.camelDashId());
            appliedPoseTick = entities.assigned(rootId, data.camelPoseTickId());
            appliedPose = entities.assigned(rootId, CompensatedEntities.POSE_DATA_ID);
            seedTransform(entities, rootId, state.body, sent.getLast());
            state.body.deltaMovement = startVelocity(entities, rootId, sent.getLast());
            // a ridden horse turns with its rider: body and head follow the yaw from its first ridden tick
            state.yawOld = state.headYaw = state.bodyYaw = state.body.yRot;
            entities.horseState(rootId, state);
        } else {
            rideMotion(entities, rootId, state.body);
            // data the server assigned since: the client stores it over its own changes
            if (entities.assigned(rootId, data.horseFlagsId()) != appliedFlags) {
                appliedFlags = entities.assigned(rootId, data.horseFlagsId());
                state.standing = data.horseFlag(32);
                state.eating = data.horseFlag(16);
            }
            if (camel && entities.assigned(rootId, data.camelDashId()) != appliedDash) {
                appliedDash = entities.assigned(rootId, data.camelDashId());
                state.dashing = data.camelDash();
            }
            if (camel && entities.assigned(rootId, data.camelPoseTickId()) != appliedPoseTick) {
                appliedPoseTick = entities.assigned(rootId, data.camelPoseTickId());
                state.lastPoseChangeTick = data.camelLastPoseChangeTick();
            }
            if (camel && entities.assigned(rootId, CompensatedEntities.POSE_DATA_ID) != appliedPose) {
                appliedPose = entities.assigned(rootId, CompensatedEntities.POSE_DATA_ID);
                state.pose = camelPose(data.pose());
            }
            // attribute packets since: each listed attribute takes the snapshot (local modifiers of others stay)
            for (var entry : snapshots.entrySet()) {
                if (!entry.getValue().equals(appliedAttributes.get(entry.getKey())))
                    state.body.attributes.apply(entry.getValue());
            }
            appliedAttributes = snapshots;
        }
        divergedInterpolation(!seeding && interpolationDiverged, gaps, notes);
        final HorseState horse = state;
        PlayerState body = horse.body;
        vehiclePosition = () -> body.position;
        float scale = rideable.scale(body.attributes.value(Attribute.SCALE));
        boolean exceptional = !entities.effects(rootId).isEmpty() || !data.alive();
        if (!entities.effects(rootId).isEmpty())
            notes.add("эффекты на транспорте");
        Pose pose = camel ? entityPose(horse.pose) : Pose.STANDING;
        var dimensions = types.livingDimensions(rideable, baby, pose, scale);
        var standing = types.livingDimensions(rideable, baby, Pose.STANDING, scale);
        body.bodyWidth = dimensions.width();
        body.bodyHeight = dimensions.height();
        // what the client knows about the mob this tick: its controller (the local player), saddle, AI and type constants
        horse.eyeHeight = dimensions.eyeHeight();
        horse.standingWidth = standing.width();
        horse.standingHeight = standing.height();
        horse.standingEyeHeight = standing.eyeHeight();
        horse.pushedByFluid = rideable.pushedByFluid();
        horse.noGravity = data.noGravity();
        horse.saddled = saddled != Tri.FALSE;
        horse.localAuthority = true;
        horse.controlledByClient = true;
        horse.playerController = true;
        horse.effectiveAi = !data.noAi();
        horse.canRear = Boolean.TRUE.equals(rideable.canRear());
        horse.navigationCanFloat = rideable.canFloat();
        horse.maxHeadYaw = rideable.maxHeadYRot();
        var vehicle = new MovementTickerHorse(env, horse);
        vehicle.gameTime(time);
        java.util.function.Supplier<HorsePassengerPhysics.Facts> seat = () -> {
            var current = types.livingDimensions(rideable, baby, camel ? entityPose(horse.pose) : Pose.STANDING, scale);
            var riders = root.passengers();
            int index = riders.indexOf(me);
            return new HorsePassengerPhysics.Facts(kind, body.position, body.yRot, horse.bodyYaw, body.height(), scale * rideable.ageScale(baby), baby, false, index, riders.size(), false, time, horse.lastPoseChangeTick, horse.oldStandAnimation, current.attachments().getClamped(ru.minecomplex.mac.entity.EntityAttachment.PASSENGER, index, body.yRot));
        };
        java.util.function.Supplier<VehiclePacketState> packetState = () -> mountPacket(horse.generation, body);
        Function<PlayerState, Vec3d> attachment = rider -> types.playerDimensions(rider.pose, rider.dimensionScale).attachments().get(EntityAttachment.VEHICLE, 0, rider.yRot);
        var ride = new HorsePlayerRide(entities.passengers(), me, rootId, vehicle, seat, packetState, attachment);
        var simulation = new HorsePlayerTick(env, vehicle, ride, p, local, playerData);
        gaps.addAll(simulation.simulateVehicleTick(time, !othersNear.test(pushReach(body)), exceptional));
        // LocalPlayer reads its vehicle after the vehicle's tick (the ride): sprint for camels, the jump charge, the dash cooldown
        riderMoves.accept(true);
        TickPackets result = simulation.simulatePassengerTick(PlayerInput.fromMask(frame.input() & 127));
        riderMoves.accept(false);
        riderSimulated = true;
        List<Object> packets = new ArrayList<>(result.packets());
        gaps.addAll(result.unmodeled());
        {
            var late = simulation.sendAfterEntityTicks();
            packets.addAll(late.packets());
            gaps.addAll(late.unmodeled());
        }
        ServerboundMoveVehiclePacket predicted = null;
        for (Object packet : packets) {
            if (packet instanceof ServerboundMoveVehiclePacket move)
                predicted = move;
        }
        ServerboundMoveVehiclePacket reported = sent.isEmpty() ? null : sent.getLast();
        if (reported != null) {
            follow(body, predicted, reported);
            horse.resolvedBaseline();
            ride.positionRider(p, local);
        }
        following = root.generation();
        startUncertainty(gaps, notes, predicted, reported, seedTransformKnown && (body.interpolation == null || !body.interpolation.active()) && !interpolationDiverged);
        // a ride that started from an assumed velocity leaves an unknown one until the mount rests
        rideVelocity = seedVelocityUnknown ? null : body.deltaMovement;
        rideVelocityEstimate = body.deltaMovement;
        simulatedBody = seedVelocityUnknown ? null : body;
        rideTransform = new RideTransform(body.position, body.yRot, body.xRot, body.interpolation, !seedVelocityUnknown && !interpolationDiverged, body.onGround);
        if (seeding)
            return skip(gaps, name + ": посадка, начальная позиция транспорта взята у клиента", name);
        Vec3d predictedPosition = predicted == null ? body.position : new Vec3d(predicted.movingTo().position().x(), predicted.movingTo().position().y(), predicted.movingTo().position().z());
        if (reported == null) {
            notes.add(predicted == null ? "клиент не отправил позицию транспорта" : "клиент не отправил позицию транспорта, а должен был");
            return new Outcome(MovementCheckRunner.Status.COMPARED, 0.0, predictedPosition, null, gaps, String.join("; ", notes), name);
        }
        if (predicted == null)
            notes.add("движок не отправил бы позицию транспорта");
        Vec3d reportedPosition = new Vec3d(reported.movingTo().position().x(), reported.movingTo().position().y(), reported.movingTo().position().z());
        return new Outcome(MovementCheckRunner.Status.COMPARED, distance(predictedPosition, reportedPosition), predictedPosition, reportedPosition, gaps, String.join("; ", notes), name);
    }

    /**
     * The server moves this horse, but Entity.rideTick still ticks the local player and leaves its velocity.
     */
    private Outcome unsaddledHorse(ClientTickFrame frame, MovementEnvironment env, CompensatedEntities entities, int id, PassengerGraph.View root, HorseState.Kind kind, PlayerState p, LocalPlayerState local, ClientPlayerData playerData, Set<Unmodeled> gaps, Long gameTime, String name, MobData data, EntityTypes.Rideable rideable) {
        var motions = entities.motions();
        var remote = motions == null ? null : motions.remotePassengerVehicle(id);
        if (remote == null || kind == HorseState.Kind.CAMEL && gameTime == null) {
            gaps.add(Unmodeled.RIDING);
            return skip(gaps, name + ": положение пассажира не определено", name);
        }
        var body = remote.body;
        float scale = rideable.scale(attributes("minecraft:" + name, entities.attributes(id)).value(Attribute.SCALE));
        var dimensions = types.livingDimensions(rideable, data.baby(), data.pose(), scale);
        var seats = new HorsePassengerPhysics();
        var context = new ru.minecomplex.mac.engine.movementtick.LocalPlayerRide() {

            public ru.minecomplex.mac.mechanics.fluid.FluidWorld.BoatPassenger boatFluidFacts() {
                return null;
            }

            public boolean nonBoatVehicleResolved() {
                return true;
            }

            public VehiclePacketState rootVehicle() {
                return new VehiclePacketState(root.generation(), Tri.FALSE, body.position, body.yRot, body.xRot, null, false, body.onGround);
            }

            public Set<Unmodeled> unmodeled() {
                return Set.of();
            }

            public boolean controlsBoat() {
                return false;
            }


            public boolean vehicleOnGround() {
                return body.onGround;
            }

            public void setBoatInput(PlayerInput input) {
                throw new IllegalStateException("Horse is not a boat");
            }

            public void positionRider(PlayerState player, LocalPlayerState hidden) {
                var seat = new HorsePassengerPhysics.Facts(kind, body.position, body.yRot, hidden.bodyYaw, body.height(), scale * rideable.ageScale(data.baby()), data.baby(), false, root.passengers().indexOf(entities.localPlayerId()), root.passengers().size(), false, gameTime == null ? 0 : gameTime, data.camelLastPoseChangeTick(), motions.horseSeatAnimation(id), dimensions.attachments().getClamped(EntityAttachment.PASSENGER, 0, body.yRot));
                var passenger = new BoatPassengerPhysics.State();
                passenger.position = player.position;
                seats.position(passenger, seat, types.playerDimensions(player.pose, player.dimensionScale).attachments().get(EntityAttachment.VEHICLE, 0, player.yRot), true);
                player.position = passenger.position;
                // The remote horse's body yaw affects rendering only, not this physical attachment.
            }
        };
        vehiclePosition = () -> body.position;
        p.oldPosition = p.position;
        local.yawOld = p.yRot;
        var passenger = new MovementTickerPlayer(env, p, local, playerData);
        riderMoves.accept(true);
        var result = passenger.simulateRideTick(PlayerInput.fromMask(frame.input() & 127), context);
        riderMoves.accept(false);
        gaps.addAll(result);
        gaps.addAll(passenger.sendAfterEntityTicks(context));
        riderSimulated = gaps.isEmpty();
        return skip(gaps, name + ": без седла, ведёт сервер; тик пассажира выполнен", name);
    }

    /**
     * {@code isSaddled()} of a horse-family mob: before 1.21.5 the saddle flag; from 1.21.5 a stack in the saddle slot that
     * {@code isEquippableInSlot}: its {@code equippable} slot is the saddle's and it may equip this type (only the vanilla
     * saddle's {@code #can_equip_saddle}, which holds every horse kind and the camel, is resolved), on a mob that
     * {@code canUseSlot(SADDLE)}: alive, adult and tamed (camels always are).
     */
    private Tri saddled(CompensatedEntities entities, int id, String type, MobData data) {
        ItemStack stack = entities.saddle(id);
        if (stack == null)
            return Tri.FALSE;
        boolean tamed = type.equals("minecraft:camel") || type.equals("minecraft:camel_husk") || data.horseFlag(2);
        if (!data.alive() || data.baby() || !tamed)
            return Tri.FALSE;
        if (!"SADDLE".equals(ItemMechanics.equippableSlot(stack)))
            return Tri.FALSE;
        boolean vanillaSaddle = Stacks.key(stack).equals("minecraft:saddle") && (!stack.getComponentsPatch().map.containsKey(DataComponents.EQUIPPABLE));
        return vanillaSaddle ? Tri.TRUE : Tri.EITHER;
    }

    /**
     * The mob's attributes as the client holds them: its type's supplier, then every snapshot the server sent.
     */
    private AttributeMap attributes(String type, java.util.Map<String, ru.minecomplex.mac.entity.attribute.AttributeSnapshot> snapshots) {
        var defaults = types.type(type).defaultAttributes();
        AttributeMap map = AttributeMap.forEntity(defaults == null ? java.util.Map.of() : defaults);
        for (var snapshot : snapshots.values()) map.apply(snapshot);
        return map;
    }

    /**
     * The camel tick's pose from the entity pose: the camel sits or stands (a dying camel keeps standing dimensions).
     */
    private static HorseState.CamelPose camelPose(Pose pose) {
        return pose == Pose.SITTING ? HorseState.CamelPose.SITTING : HorseState.CamelPose.STANDING;
    }

    private static Pose entityPose(HorseState.CamelPose pose) {
        return pose == HorseState.CamelPose.SITTING ? Pose.SITTING : Pose.STANDING;
    }

    /**
     * The velocity a mob has before the client controls it, for the seeded first tick. A mob the client reports on the
     * ground after that tick stood there: the server's resting value ({@code (0 - 0.08) * 0.98F}), and any downward
     * velocity is cancelled by the floor in that tick, so the result does not depend on it. Otherwise it is not known
     * (taken as zero; the next tick shows the difference).
     */
    private static Vec3d restingVelocity(ServerboundMoveVehiclePacket after) {
        return after.onGround() ? new Vec3d(0.0, -0.08 * 0.98F, 0.0) : Vec3d.ZERO;
    }

    private static String paddleText(ServerboundPaddleBoatPacket paddles) {
        return (paddles.getLeft() ? "Л" : "-") + (paddles.getRight() ? "П" : "-");
    }

    /**
     * The boat at a client-reported transform: body, the motion's copy of it, and the reported ground flag.
     */
    private static void place(BoatState state, ServerboundMoveVehiclePacket at) {
        placeAt(state, new Vec3d(at.movingTo().position().x(), at.movingTo().position().y(), at.movingTo().position().z()), at.movingTo().yRot(), at.movingTo().xRot(), at.onGround());
    }

    private static void placeAt(BoatState state, Vec3d position, float yaw, float pitch, boolean onGround) {
        state.body.position = position;
        state.body.yRot = yaw;
        state.body.xRot = pitch;
        state.body.onGround = onGround;
    }

    /**
     * {@code positionRider}'s position for the adopted boat; the seat rotation was already applied by the tick.
     */
    private void reseat(BoatState state, PlayerState p, LocalPlayerState local, PassengerGraph.View root, int me, boolean raft, boolean chest, Vec3d attachment) {
        var seat = new BoatPassengerPhysics.State();
        seat.position = p.position;
        seat.yaw = p.yRot;
        seat.yawOld = local.yawOld;
        seat.headYaw = local.headYaw;
        seat.bodyYaw = local.bodyYaw;
        new BoatPassengerPhysics().position(seat, state.body.position, state.body.yRot, state.deltaRotation, state.body.height(), raft, chest, root.passengers().size(), root.passengers().indexOf(me), attachment, false, true);
        p.position = seat.position;
    }

    private static boolean noGravity(CompensatedEntities entities, int id) {
        var entity = entities.entity(id);
        return entity != null && entity.spawn() != null && id != entities.localPlayerId() && entity.noGravity();
    }

    private Outcome skip(Set<Unmodeled> gaps, String note, String vehicle) {
        return new Outcome(MovementCheckRunner.Status.SKIPPED, 0.0, null, null, gaps, note, vehicle);
    }

    static double distance(Vec3d a, Vec3d b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
