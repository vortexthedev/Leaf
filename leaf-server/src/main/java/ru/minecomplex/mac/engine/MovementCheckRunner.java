package ru.minecomplex.mac.engine;

import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;
import net.minecraft.core.component.DataComponents;
import ru.minecomplex.mac.inventory.item.ItemMechanics;
import ru.minecomplex.mac.listener.MultiBlockChangeBitRepackHandler;
import ru.minecomplex.mac.inventory.item.Stacks;
import ru.minecomplex.mac.entity.EntityDataItems;
import ru.minecomplex.mac.packet.MoveKind;
import ru.minecomplex.mac.packet.Packets;
import net.minecraft.world.entity.Relative;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.entity.EntityMotions;
import ru.minecomplex.mac.entity.effect.MobEffect;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.engine.movementtick.MovementTickerPlayer;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.listener.ClientPlayerActions;
import ru.minecomplex.mac.player.state.ClientPlayerData;
import ru.minecomplex.mac.player.PlayerModel;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.replica.WorldModel;

/**
 * Diagnostic replay of one client, not a check: a single chain of world and player hypotheses that applies, before
 * every client tick, the changes the client certainly processed ({@link SyncWindow#certainThrough()}), the client's
 * own inventory actions, then simulates the tick with the frame's keys and rotation and compares the predicted
 * position with the one the client sent. After every tick the simulated position is moved to the reported one, so each
 * tick is measured on its own (the simulated velocity is kept).
 *
 * <p>What it does not resolve is reported with the tick instead of guessed away: changes the client may or may not
 * have processed ({@link TickReport#inFlight()}), item use (assumed not in use), the world border (none), client options
 * (auto-jump off, sprint window 7). While the player rides, the root vehicle is compared instead ({@link VehicleCheckRunner}).
 *
 * <p>Player changes in flight: a client may handle a change in a frame before the one where it answers the marker after
 * it (the marker reached it later), so a tick can have used a change that was still uncertain. Its effect often shows
 * only in the velocity the tick leaves (a removed levitation changes the next position, not this one), and a single chain
 * waiting for the marker would carry a wrong velocity on. While the player does not ride, the probe therefore keeps the
 * client states that applied prefixes of the in-flight changes that move the player ({@link #branch}, at most
 * {@link #MAX_CHAINS}, extended to {@link #MAX_LOCAL_CHAINS} for local hotbar choices); every tick simulates each,
 * keeps those closest to the reported position and, of those that
 * applied the same changes, the oldest ({@link #select}). World changes in flight stay unapplied.
 *
 * <p>Other entities: boats, rafts, happy ghasts and the other living entities are simulated in the world hypothesis
 * ({@code EntityMotions}) and ticked where the client's tick list puts them, before or after the local player; boats' and
 * happy ghasts' boxes are the entity collisions of the player and of its vehicle, and every one of them pushes the player
 * in its tick. Entities whose box or pushes are not modelled are reported ({@link Unmodeled#ENTITY_COLLISION},
 * {@link Unmodeled#ENTITY_PUSH}, {@link Unmodeled#VEHICLE_ENTITIES}).
 */
public final class MovementCheckRunner {

    /**
     * The outcome of one client tick.
     *
     * @param inFlight player and world changes the server had sent that the client may already have processed but the
     *                 probe did not apply (no acknowledged marker after them yet), by packet name
     * @param vehicle  the ridden root vehicle's type path when the positions are the vehicle's ({@code move_vehicle}),
     *                 {@code null} for the player's own
     */
    public record TickReport(long frame, Status status, double offset, Vec3d predicted, Vec3d reported, List<String> inFlight, Set<Unmodeled> gaps, String note, String vehicle) {

        public boolean windowOpen() {
            return !inFlight.isEmpty();
        }
    }

    public enum Status {

        /**
         * Simulated and compared with a reported position (or with the last one when none was sent).
         */
        COMPARED,
        /**
         * Not simulated: {@link TickReport#note()} says why.
         */
        SKIPPED
    }

    /**
     * The client player states still possible, oldest first; the first one reports ({@link #lead()}).
     */
    private final List<Chain> chains = new ArrayList<>();

    private final WorldModel.Hypothesis world;

    private final BlockRegistry registry;

    private volatile BlockTags tags = BlockTags.NONE;

    private volatile ru.minecomplex.mac.world.Precipitation.Source biomes = ru.minecomplex.mac.world.Precipitation.Source.UNKNOWN;

    private Vec3d lastReported;

    private final VehicleCheckRunner vehicles;

    private final ru.minecomplex.mac.inventory.item.ItemBehavior items;

    private final ru.minecomplex.mac.mechanics.movement.FireworkBoost fireworks;

    private final ru.minecomplex.mac.entity.EntityTypes entityTypes;

    /**
     * A firework rocket change in flight that a client state may have handled got no state of its own (the states were
     * at their limit, or a change before it in flight is not branched on): the velocity the frame leaves is a guess.
     */
    private boolean rocketForksIncomplete;

    /**
     * Some possible hotbar stacks were unknown or could not get a local-input hypothesis.
     */
    private boolean hotbarForksIncomplete;

    /**
     * What the frame's changes could not resolve before the tick (blocks set back by an acknowledgement): reported with it.
     */
    private final Set<Unmodeled> syncGaps = EnumSet.noneOf(Unmodeled.class);

    /**
     * An entity that ticks after the player may have pushed it: reported with the next tick.
     */
    private boolean pushedAfter;

    /**
     * What the pushes of the entities ticking after the player could not resolve: reported with the next tick.
     */
    private final Set<Unmodeled> pushGapsAfter = EnumSet.noneOf(Unmodeled.class);

    /**
     * What the pushes of the entities ticking before the player could not resolve this tick.
     */
    private final Set<Unmodeled> pushGapsBefore = EnumSet.noneOf(Unmodeled.class);

    /**
     * What the block entities' moves of the player could not resolve after the player sent its position (through 26.2
     * they tick after it): reported with the next tick.
     */
    private final Set<Unmodeled> blockGapsAfter = EnumSet.noneOf(Unmodeled.class);

    /**
     * Gaps after which the velocity a tick leaves is a guess ({@link Chain#velocityGaps}).
     */
    private static final List<Unmodeled> VELOCITY_GAPS = List.of(Unmodeled.WORLD_BORDER_CLOCK, Unmodeled.RIPTIDE, Unmodeled.SLEEP_TIMING);

    /**
     * The player changes in flight some state was branched to have handled ({@link #branch}), by sequence.
     */
    private final java.util.Set<Long> forkedPlayerChanges = new java.util.HashSet<>();

    /**
     * How many client player states the probe keeps at most (the lead and its alternatives).
     */
    static final int MAX_CHAINS = 8;

    /**
     * Temporary local-input alternatives, below PlayerModel's 64-state ownership limit.
     */
    static final int MAX_LOCAL_CHAINS = 32;

    /**
     * One possible client player: a hypothesis with its own history of when the player changes were applied.
     */
    private static final class Chain {

        final PlayerModel.Hypothesis player;

        /**
         * The player's velocity is the one a ride the probe did not simulate left ({@code rideTick} zeroes it, then the
         * passenger's own tick moves it, and a dismount teleport keeps it): reported until it is known again.
         */
        boolean carriedVelocityUnknown;

        /**
         * A tick this state compared was uncertain about other entities' collisions or pushes: the velocity it left is this
         * model's guess, reported until it is known again (as {@link #carriedVelocityUnknown}).
         */
        boolean entityVelocityUnknown;

        /**
         * Gaps of a tick this state compared that left its velocity a guess as well ({@link #VELOCITY_GAPS}: the world
         * border's phase near the player, a riptide launch or touch): reported until a tick proves the velocity.
         */
        final EnumSet<Unmodeled> velocityGaps = EnumSet.noneOf(Unmodeled.class);

        String riptideSource = "";

        /**
         * The changes this state applied before their marker was answered, named when it becomes the lead.
         */
        final List<String> early = new ArrayList<>();

        /**
         * Whether the player ticks in this frame ({@code hasClientLoaded}).
         */
        boolean ticked;

        /**
         * A hotbar key may have selected this tick's hand after gameMode.tick sent the last known slot.
         */
        boolean unannouncedSlot;

        Chain(PlayerModel.Hypothesis player) {
            this.player = player;
        }

        Chain fork() {
            Chain fork = new Chain(player.fork());
            fork.carriedVelocityUnknown = carriedVelocityUnknown;
            fork.entityVelocityUnknown = entityVelocityUnknown;
            fork.velocityGaps.addAll(velocityGaps);
            fork.riptideSource = riptideSource;
            fork.early.addAll(early);
            fork.unannouncedSlot = unannouncedSlot;
            return fork;
        }
    }

    public MovementCheckRunner(WorldModel worldModel, PlayerModel playerModel, SyncWindow window) {
        this.registry = worldModel.registry();
        this.world = worldModel.hypothesis(window, window.certainThrough());
        this.chains.add(new Chain(playerModel.start(new PlayerState(), new LocalPlayerState(), new ClientPlayerData(), window.certainThrough())));
        this.vehicles = new VehicleCheckRunner();
        this.items = ru.minecomplex.mac.inventory.item.ItemBehavior.get();
        this.fireworks = new ru.minecomplex.mac.mechanics.movement.FireworkBoost();
        this.entityTypes = ru.minecomplex.mac.entity.EntityTypes.get();
    }

    /**
     * The world hypothesis the probe ticks (diagnostics and tests; {@code null} before login).
     */
    public CompensatedWorld clientWorld() {
        return world.world();
    }

    /**
     * Read-only, bounded post-tick context from the same client hypothesis that produced the report.
     */
    public String diagnosticState() {
        var state = lead().player();
        var data = lead().data();
        var out = new StringBuilder(4096);
        out.append(vehicles.diagnosticState()).append('\n');
        out.append("player position=").append(state.position).append(" velocity=").append(state.deltaMovement).append(" yaw=").append(state.yRot).append(" pitch=").append(state.xRot).append(" onGround=").append(state.onGround).append(" pose=").append(state.pose).append(" sprint=").append(state.sprinting).append(" swim=").append(state.swimming).append(" gliding=").append(state.fallFlying).append(" passenger=").append(state.passenger).append(" water=").append(state.waterHeight).append(" lava=").append(state.lavaHeight).append(" collisions=").append(state.horizontalCollision).append('/').append(state.verticalCollision).append(" riptide=").append(state.autoSpinAttackTicks).append(" cause=").append(state.riptideUnknownCause).append(" hypotheses=").append(chains.size()).append('\n');
        out.append("inventory selected=").append(data.inventory.selected()).append(" carried=").append(data.carriedIndex).append(" using=").append(data.usingItem).append(" useRemaining=").append(data.useRemaining).append('\n');
        out.append("attributes:");
        for (var attribute : ru.minecomplex.mac.entity.attribute.Attribute.values()) out.append(' ').append(attribute).append('=').append(state.attributes.value(attribute));
        out.append("\neffects:");
        for (var effect : ru.minecomplex.mac.entity.effect.MobEffect.values()) if (state.effects.has(effect))
            out.append(' ').append(effect).append('=').append(state.effects.amplifier(effect));
        out.append('\n');
        for (int slot : new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 40 }) {
            out.append("slot ").append(slot).append(" item=").append(data.inventory.item(slot)).append(" minCount=").append(data.inventory.minCount(slot)).append(" unknown=").append(data.inventory.unknown(slot)).append('\n');
        }
        var level = clientWorld();
        if (level == null)
            return out.toString();
        out.append("dimension=").append(level.dimension()).append(" time=").append(level.gameTime()).append('\n');
        var entities = level.entities();
        if (entities != null && entities.motions() != null) {
            var motions = entities.motions();
            var root = entities.passengers().root(entities.localPlayerId());
            var near = entities.entityIds().stream().filter(id -> {
                var at = motions.position(id);
                return java.util.Objects.equals(root, id) || at == null || at.subtract(state.position).lengthSqr() <= 256;
            }).sorted(java.util.Comparator.<Integer>comparingInt(id -> java.util.Objects.equals(root, id) ? 0 : 1).thenComparingInt(Integer::intValue)).toList();
            out.append("entities within 16 blocks (unknown positions included): ").append(near.size()).append('\n');
            for (int id : near.stream().limit(32).toList()) {
                out.append("entity ").append(id).append(' ').append(entities.typeOf(id)).append(' ').append(motions.describe(id)).append(" graph=").append(entities.passengers().get(id)).append(" metadata=").append(entities.mobItems(id)).append(" attributes=").append(entities.attributes(id)).append(" effects=").append(entities.effects(id)).append('\n');
                out.append("equipment saddle=").append(entities.saddle(id)).append(" body=").append(entities.body(id)).append('\n');
                var ride = entities.livingState(id);
                if (ride != null)
                    out.append("living body=").append(ride.body.position).append(" velocity=").append(ride.body.deltaMovement).append(" boost=").append(ride.control.boosting).append(' ').append(ride.control.boostTime).append('/').append(ride.control.boostDuration).append(" dash=").append(ride.control.dashCooldown).append('\n');
            }
            if (near.size() > 32)
                out.append("entity text truncated at 32; complete wire history is in recording.macr\n");
        }
        int x = (int) Math.floor(state.x()), y = (int) Math.floor(state.y()), z = (int) Math.floor(state.z());
        out.append("local blocks (7x5x7, state IDs; air omitted, -1 unloaded):\n");
        for (int dx = -3; dx <= 3; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -3; dz <= 3; dz++) {
            int block = level.getBlock(x + dx, y + dy, z + dz);
            if (block != 0)
                out.append(x + dx).append(',').append(y + dy).append(',').append(z + dz).append('=').append(block).append(' ');
        }
        out.append('\n');
        return out.length() <= 32768 ? out.toString() : out.substring(0, 32768) + "\n[text truncated; see recording.macr]\n";
    }

    /**
     * The block tags before the client's first {@code update_tags} reached the model (a recording's tags file; the adapter
     * feeds the configuration phase's {@code update_tags} as a world change); without them no block is in any tag.
     */
    public void tags(BlockTags tags) {
        this.tags = tags;
    }

    /**
     * The block tags of the client's level: its last {@code update_tags}, else {@link #tags(BlockTags)}.
     */
    private BlockTags tags(CompensatedWorld level) {
        BlockTags handled = level == null ? null : level.tags();
        return handled != null ? handled : tags;
    }

    public void biomes(ru.minecomplex.mac.world.Precipitation.Source biomes) {
        this.biomes = biomes;
    }

    /**
     * The player state the probe reports (the other possible states are alternatives to it).
     */
    private PlayerModel.Hypothesis lead() {
        return chains.get(0).player;
    }

    /**
     * One client tick, on the session's thread, after the session settled the frame's window.
     */
    public TickReport frame(ClientTickFrame frame) {
        SyncWindow window = frame.window();
        syncGaps.clear();
        prepareEarlyController(frame);
        sleepsLate(window);
        // the world changes still unapplied that the client may have handled during the last tick
        long[] lateSeqs = world.pendingSeqs(lastPossibleBefore);
        var late = world.pendingBefore(lastPossibleBefore);
        long[] borderSeqs = world.pendingSeqs(Long.MAX_VALUE);
        var borderChanges = world.pendingBefore(Long.MAX_VALUE);
        for (int i = 0; i < lateSeqs.length; i++) {
            if (Packets.border(late.get(i)))
                borderLate.merge(lateSeqs[i], 1, Integer::sum);
        }
        List<Object> packets = frame.packets();
        // screen packets the client sent from its input handling, before the drain that preceded the tick: each saw only
        // the changes certain at it
        int screen = 0;
        while (screen < packets.size() && Packets.betweenTicks(packets.get(screen)) && frame.packetWindows().get(screen).certainThrough() < window.certainThrough()) {
            applyCertain(frame.packetWindows().get(screen));
            act(packets.get(screen));
            screen++;
        }
        applyCertain(window);
        lateMoves(late, lateSeqs, window);
        // a vehicle movement of this tick proves the client handled the mount whose marker it has not answered yet
        SyncWindow proven = mountProven(frame, window);
        // and a tick without a passenger's rotation-only movement proves the client handled the dismount
        boolean dismount = false;
        if (proven == null) {
            proven = dismountProven(frame, window);
            dismount = proven != null;
        }
        if (proven != null) {
            window = proven;
            applyCertain(window);
            lateMoves(late, lateSeqs, window);
        }
        borderPhase(borderSeqs, borderChanges, frame);
        lastPossibleBefore = window.possibleBefore();
        rocketForksIncomplete = false;
        if (lead().player().passenger) {
            retireAlternatives();
            rocketForksIncomplete = rocketForkPoints(chains.get(0).player.appliedThrough(), window.possibleBefore()).length > 0;
        } else {
            branch(window);
        }
        // handleKeybinds runs before the tick with the camera's rotation, which the tick's movement then sends (a riptide
        // launches along it); an own interpolation's step changes the rotation sent: what the launch read is not known
        ServerboundMovePlayerPacket turned = frame.movement();
        boolean release = false;
        for (int i = screen; i < packets.size(); i++) {
            release |= packets.get(i) instanceof ServerboundPlayerActionPacket action && action.getAction() == net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM;
        }
        if (turned != null && turned.hasRotation()) {
            for (Chain chain : chains) {
                PlayerState p = chain.player.player();
                if (p.passenger)
                    continue;
                if (p.interpolation != null && p.interpolation.active()) {
                    if (release) {
                        p.riptideUnknownCause = "поворот при отпускании трезубца во время интерполяции игрока";
                        syncGaps.add(Unmodeled.RIPTIDE);
                    }
                    continue;
                }
                p.yRot = turned.getYRot(0);
                p.xRot = turned.getXRot(0);
            }
        }
        for (int i = screen; i < packets.size(); i++) act(packets.get(i));
        branchHotbar(frame);
        var ridingLevel = world.world();
        var ridingEntities = ridingLevel == null ? null : ridingLevel.entities();
        if (ridingEntities != null && ridingEntities.motions() != null) {
            Integer root = ridingEntities.passengers().root(ridingEntities.localPlayerId());
            if (root != null && root != ridingEntities.localPlayerId()) {
                Tri tickController = vehicles.controllerForTick(ridingEntities, root, lead().data(), frame, lead().data().clientLoaded);
                if (tickController != vehicles.controller(ridingEntities, root, lead().data()) && ridingEntities.motions().earlyControllerStart(root) == null) {
                    // A hotbar key changed control after gameMode.tick. Its slot packet cannot tell whether an earlier
                    // wire movement read the old or new hand; retain that uncertainty beyond this transition tick.
                    for (int i = 0; i < borderChanges.size(); i++) {
                        if (borderSeqs[i] > world.appliedThrough())
                            continue;
                        var change = borderChanges.get(i);
                        boolean moved = switch(change) {
                            case ClientboundTeleportEntityPacket move ->
                                move.id() == root;
                            case ClientboundEntityPositionSyncPacket move ->
                                move.id() == root;
                            default ->
                                Packets.entityMove(change) && Packets.entityId(change) == root;
                        };
                        if (moved) {
                            ridingEntities.motions().uncertainController(root);
                            syncGaps.add(Unmodeled.RIDING);
                        }
                    }
                }
                ridingEntities.motions().localController(root, tickController);
            }
        }
        // LocalPlayer.tick runs once the client has loaded: LivingEntity.tick starts with updatingUsingItem, Player.tick
        // ends with the item cooldowns; the client tick ends after the entity ticks
        for (Chain chain : chains) {
            chain.ticked = chain.player.data().clientLoaded;
            if (chain.ticked)
                chain.player.livingTickStart();
        }
        // ClientLevel.tickEntities: the entities before the local player in the tick list, the player, the others
        CompensatedWorld level = world.world();
        pushGapsBefore.clear();
        if (level != null)
            level.tickEntities(true, bodyQueries(level), pushTarget(level), pushGapsBefore, fluidPush(level), climbable(level));
        afterTicksDone = false;
        blockEntitiesDone = false;
        TickReport[] reports = move(frame, window);
        if (level != null && !afterTicksDone)
            tickEntitiesAfter(level);
        blockGapsAfter.clear();
        if (level != null && !blockEntitiesDone) {
            tickBlockEntities(level, blockGapsAfter);
            // 26.3 sends after the block entities: a tick the probe did not split carries their gaps now
            if (!blockGapsAfter.isEmpty()) {
                for (int i = 0; i < reports.length; i++) reports[i] = withGaps(reports[i], blockGapsAfter);
                blockGapsAfter.clear();
            }
        }
        for (Chain chain : chains) {
            if (chain.ticked)
                chain.player.playerTickEnd();
            chain.player.tickEnded();
        }
        // ClientLevel.tick after the entity ticks: the world border, then tickTime
        if (world.world() != null) {
            world.world().tickBorder();
            world.world().advanceGameTime();
            // the frame that may follow this tick polls the light queue and relights changed blocks
            world.world().tickLight();
        }
        TickReport report = select(reports);
        if (proven != null) {
            report = withNote(report, dismount ? "высадка по движению клиента (маркер ещё в пути)" : "посадка по move_vehicle клиента (маркер ещё в пути)");
        }
        String lids = level == null ? "" : lidNote(level);
        if (lids.isEmpty())
            return report;
        return new TickReport(report.frame(), report.status(), report.offset(), report.predicted(), report.reported(), report.inFlight(), report.gaps(), report.note().isEmpty() ? lids : report.note() + "; " + lids, report.vehicle());
    }

    /**
     * Shulker lids near the player that moved or are open, last frame.
     */
    private final Set<ru.minecomplex.mac.world.BlockPosition> activeLids = new java.util.HashSet<>();

    /**
     * Diagnostics: the shulker lids around the player that are not closed or closed in this tick, as the block entities
     * left them ({@code progressOld→progress}; the next tick's movement collides with them).
     */
    private String lidNote(CompensatedWorld level) {
        var lids = level.shulkersIn(lead().player().boundingBox().inflate(2.0));
        List<String> parts = new ArrayList<>();
        Set<ru.minecomplex.mac.world.BlockPosition> active = new java.util.HashSet<>();
        lids.forEach((pos, lid) -> {
            if (lid.isClosed() && !activeLids.contains(pos))
                return;
            if (!lid.isClosed())
                active.add(pos);
            parts.add(String.format(java.util.Locale.ROOT, "крышка %d %d %d %s %.2f→%.2f", pos.x(), pos.y(), pos.z(), lid.status(), lid.progressOld(), lid.progress()));
        });
        activeLids.clear();
        activeLids.addAll(active);
        return String.join(", ", parts);
    }

    /**
     * The window raised to the in-flight {@code set_passengers} that seats the player, when this tick's packets show the
     * client already rides: {@code LocalPlayer.sendPosition} sends {@code move_vehicle} ({@code paddle_boat}) only for a
     * root vehicle it controls, and it handles clientbound packets in order, so it handled that change and everything
     * before it although the marker after it is not answered yet. {@code null} when there is no such evidence.
     */
    private SyncWindow mountProven(ClientTickFrame frame, SyncWindow window) {
        if (lead().player().passenger)
            return null;
        boolean controls = false;
        for (Object packet : frame.packets()) {
            if (packet instanceof ServerboundMoveVehiclePacket || packet instanceof ServerboundPaddleBoatPacket)
                controls = true;
        }
        CompensatedWorld level = world.world();
        var entities = level == null ? null : level.entities();
        if (!controls || entities == null)
            return null;
        int me = entities.localPlayerId();
        long[] seqs = world.pendingSeqs(window.possibleBefore());
        var changes = world.pendingBefore(window.possibleBefore());
        for (int i = 0; i < seqs.length; i++) {
            if (changes.get(i) instanceof ClientboundSetPassengersPacket passengers && java.util.Arrays.stream(passengers.getPassengers()).anyMatch(id -> id == me)) {
                return new SyncWindow(seqs[i], window.possibleBefore());
            }
        }
        return null;
    }

    /**
     * Client ticks each unapplied world-border change may already have run on the client (it was late at that many frames).
     */
    private final java.util.Map<Long, Integer> borderLate = new java.util.HashMap<>();

    /**
     * The border lerp applied in this frame may run ahead of the model by the ticks the client may have handled it before
     * ({@link CompensatedWorld#borderAhead}): a lerp sets a new phase, a static size ends it, a new center keeps it.
     *
     * @param seqs    the world changes pending when the frame began
     * @param changes those changes
     */
    private void borderPhase(long[] seqs, List<Object> changes, ClientTickFrame frame) {
        long[] rest = world.pendingSeqs(Long.MAX_VALUE);
        long firstRest = rest.length == 0 ? Long.MAX_VALUE : rest[0];
        Integer ahead = null;
        for (int i = 0; i < seqs.length && seqs[i] < firstRest; i++) {
            var border = changes.get(i);
            if (!Packets.border(border))
                continue;
            Integer lateTicks = borderLate.remove(seqs[i]);
            boolean lerp = border instanceof ClientboundSetBorderLerpSizePacket || border instanceof ClientboundInitializeBorderPacket initialize && initialize.getLerpTime() > 0;
            if (lerp)
                ahead = lateTicks == null ? 0 : lateTicks;
            else if (!(border instanceof ClientboundSetBorderCenterPacket))
                ahead = 0;

        }
        if (ahead != null && ahead > 0 && world.world() != null)
            world.world().borderAhead(ahead);
    }

    /**
     * This tick has an entity change in flight near the player that could move or reshape an entity ({@link #mayMoveEntity}).
     */
    private boolean entityInFlight;

    /**
     * Whether an in-flight entity change near the player could matter to it this tick: not a move, sync or teleport to the
     * transform the entity already rests at, not a velocity (a remote entity does not move by it).
     */
    private static boolean mayMoveEntity(Object change, EntityMotions motions) {
        return switch(change) {
            case ClientboundEntityPositionSyncPacket sync ->
                {
                    var end = sync.position().endPosition();
                    yield motions == null || !motions.restsAt(sync.id(), new Vec3d(end.x(), end.y(), end.z()));
                }
            case ClientboundTeleportEntityPacket teleport ->
                {
                    int relatives = net.minecraft.world.entity.Relative.pack(teleport.relatives());
                    var position = teleport.change().position();
                    yield motions == null || ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.X) || ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.Y) || ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.Z) || !motions.restsAt(teleport.id(), new Vec3d(position.x(), position.y(), position.z()));
                }
            case ClientboundSetEntityMotionPacket ignored ->
                false;
            // the other entity changes: spawn, removal, passengers, data, attributes, equipment
            case ClientboundAddEntityPacket ignored ->
                true;
            case ClientboundRemoveEntitiesPacket ignored ->
                true;
            case ClientboundSetPassengersPacket ignored ->
                true;
            case ClientboundSetEntityDataPacket ignored ->
                true;
            case ClientboundUpdateAttributesPacket ignored ->
                true;
            case ClientboundSetEquipmentPacket ignored ->
                true;
            default ->
                {
                    if (!Packets.entityMove(change))
                        yield false;
                    var delta = Packets.delta(change);
                    yield delta != null && (motions == null || !Packets.zeroDelta(delta) || !motions.restsAt(Packets.entityId(change), null));
                }
        };
    }

    /**
     * The last tick's {@code possibleBefore}: the world changes before it may have been handled before that tick.
     */
    private long lastPossibleBefore = 0;

    /**
     * Entity moves that were in flight during an earlier tick and are certain now: the client may have handled them before
     * that tick, a step ahead of this model ({@link EntityMotions#phaseUncertain}).
     */
    private void lateMoves(List<Object> late, long[] lateSeqs, SyncWindow window) {
        CompensatedWorld level = world.world();
        var motions = level == null || level.entities() == null ? null : level.entities().motions();
        if (motions == null)
            return;
        for (int i = 0; i < lateSeqs.length; i++) {
            if (lateSeqs[i] > window.certainThrough())
                break;
            var change = late.get(i);
            switch(change) {
                case ClientboundEntityPositionSyncPacket sync ->
                    motions.phaseUncertain(sync.id());
                case ClientboundTeleportEntityPacket teleport ->
                    motions.phaseUncertain(teleport.id());
                // set a tick early, the client may have slowed it once more since
                case ClientboundSetEntityMotionPacket velocity ->
                    motions.velocityLate(velocity.id());
                default ->
                    {
                        if (Packets.entityMove(change))
                            motions.phaseUncertain(Packets.entityId(change));
                    }
            }
        }
    }

    /**
     * A tick of a loaded client without a rotation-only movement is no passenger's ({@code LocalPlayer.tick} sends a
     * {@code Rot} every tick while riding, {@code sendPosition} otherwise: a position, a status or nothing): the
     * in-flight change that leaves the local player without its vehicle ({@code set_passengers} of the vehicle without
     * it, the vehicle's removal), before the marker the client has not answered yet, was handled.
     */
    private SyncWindow dismountProven(ClientTickFrame frame, SyncWindow window) {
        if (!lead().player().passenger || !lead().data().clientLoaded)
            return null;
        ServerboundMovePlayerPacket tick = frame.movement();
        if (tick != null && Packets.kind(tick) == MoveKind.ROT)
            return null;
        CompensatedWorld level = world.world();
        var entities = level == null ? null : level.entities();
        if (entities == null)
            return null;
        int me = entities.localPlayerId();
        var own = entities.passengers().get(me);
        Integer vehicle = own == null ? null : own.vehicle();
        if (vehicle == null)
            return null;
        long[] seqs = world.pendingSeqs(window.possibleBefore());
        var changes = world.pendingBefore(window.possibleBefore());
        for (int i = 0; i < seqs.length; i++) {
            boolean leaves = switch(changes.get(i)) {
                case ClientboundSetPassengersPacket passengers ->
                    passengers.getVehicle() == vehicle && java.util.Arrays.stream(passengers.getPassengers()).noneMatch(id -> id == me);
                case ClientboundRemoveEntitiesPacket removed ->
                    removed.entityIds().intStream().anyMatch(id -> id == vehicle);
                default ->
                    false;
            };
            if (leaves)
                return new SyncWindow(seqs[i], window.possibleBefore());
        }
        return null;
    }

    private static TickReport withNote(TickReport report, String note) {
        return new TickReport(report.frame(), report.status(), report.offset(), report.predicted(), report.reported(), report.inFlight(), report.gaps(), report.note().isEmpty() ? note : report.note() + "; " + note, report.vehicle());
    }

    /**
     * A wheel selection can precede packet processing even though set_carried_item follows the marker replies.
     */
    private void prepareEarlyController(ClientTickFrame frame) {
        var level = world.world();
        var entities = level == null ? null : level.entities();
        if (entities == null || entities.motions() == null)
            return;
        Integer root = entities.passengers().root(entities.localPlayerId());
        Integer candidate = null;
        if (root != null && root != entities.localPlayerId() && vehicles.controller(entities, root, lead().data()) == Tri.FALSE && vehicles.controllerForTick(entities, root, lead().data(), frame, lead().data().clientLoaded) == Tri.TRUE) {
            candidate = root;
        }
        entities.motions().beginEarlyController(candidate);
    }

    /**
     * A serverbound packet of the client, after the changes it saw: its client-local effect in every state kept.
     */
    private void act(Object packet) {
        predict(packet);
        // LocalPlayer.tick runs (and sends input and movement) only once hasClientLoaded(): player_loaded or any
        // packet of the player tick shows it, also when the login/respawn entered the certain prefix late
        boolean loaded = packet instanceof ServerboundPlayerLoadedPacket || packet instanceof ServerboundMovePlayerPacket || packet instanceof ServerboundPlayerInputPacket;
        for (Chain chain : chains) {
            if (packet instanceof ServerboundSetCarriedItemPacket || packet instanceof ServerboundUseItemPacket || packet instanceof ServerboundUseItemOnPacket || packet instanceof ServerboundInteractPacket || packet instanceof ServerboundAttackPacket)
                chain.unannouncedSlot = false;
            if (loaded)
                chain.player.data().clientLoaded = true;
            chain.player.act(packet, target(world.world(), chain));
        }
        syncPassengers();
    }

    /**
     * Minecraft.tick sends the carried slot in gameMode.tick, then handleKeybinds may select another slot before
     * LivingEntity.updatingUsingItem. A use/attack/interaction flushes that selection before movement; otherwise its
     * packet can arrive next tick. Only actual known hotbar stacks are simulated, through the normal use tick.
     */
    private void branchHotbar(ClientTickFrame frame) {
        hotbarForksIncomplete = false;
        for (var packet : frame.packets()) {
            if (packet instanceof ServerboundUseItemPacket || packet instanceof ServerboundUseItemOnPacket || packet instanceof ServerboundInteractPacket || packet instanceof ServerboundAttackPacket)
                return;
        }
        int original = chains.size();
        for (int i = 0; i < original; i++) {
            Chain chain = chains.get(i);
            var data = chain.player.data();
            if (!data.clientLoaded || !data.usingItem || data.usingOffHand || chain.player.player().passenger || chain.player.player().spectator || data.inventory.openContainerId() != 0)
                continue;
            var seen = new java.util.HashSet<net.minecraft.world.item.ItemStack>();
            seen.add(data.inventory.item(data.inventory.selected()));
            for (int slot = 0; slot < 9; slot++) {
                if (data.inventory.unknown(slot) || data.inventory.minCount(slot) != Stacks.count(data.inventory.item(slot))) {
                    syncGaps.add(Unmodeled.ITEM_USE_TIMING);
                    hotbarForksIncomplete = true;
                    continue;
                }
                if (!seen.add(data.inventory.item(slot)))
                    continue;
                if (chains.size() >= MAX_LOCAL_CHAINS) {
                    syncGaps.add(Unmodeled.ITEM_USE_TIMING);
                    hotbarForksIncomplete = true;
                    break;
                }
                Chain fork = chain.fork();
                fork.player.data().inventory.setSelected(slot);
                fork.unannouncedSlot = true;
                chains.add(fork);
            }
        }
    }

    /**
     * The changes the client certainly processed, world and player ones in wire order: the riding state of the player
     * lives in the world's passenger graph, and a teleport received while riding is acknowledged but not applied
     * ({@code handleMovePlayer}). Every player state catches up from where it is.
     */
    private void applyCertain(SyncWindow window) {
        long worldThrough = Math.max(window.certainThrough(), world.appliedThrough());
        long[] worldSeqs = world.pendingSeqs(worldThrough + 1);
        int count = chains.size();
        long[] through = new long[count];
        long[][] playerSeqs = new long[count][];
        List<List<Object>> playerChanges = new ArrayList<>();
        int[] next = new int[count];
        for (int c = 0; c < count; c++) {
            var player = chains.get(c).player;
            through[c] = Math.max(window.certainThrough(), player.appliedThrough());
            playerSeqs[c] = player.pendingSeqs(through[c] + 1);
            playerChanges.add(player.pendingBefore(through[c] + 1));
        }
        for (int w = 0; ; ) {
            long seq = Long.MAX_VALUE;
            for (int c = 0; c < count; c++) {
                if (next[c] < playerSeqs[c].length)
                    seq = Math.min(seq, playerSeqs[c][next[c]]);
            }
            if (w < worldSeqs.length && worldSeqs[w] <= seq) {
                world.applyStep(worldSeqs[w++]);
                snapBack();
            } else if (seq == Long.MAX_VALUE) {
                break;
            } else {
                boolean teleport = false;
                for (int c = 0; c < count; c++) {
                    if (next[c] >= playerSeqs[c].length || playerSeqs[c][next[c]] != seq)
                        continue;
                    Chain chain = chains.get(c);
                    var change = playerChanges.get(c).get(next[c]);
                    teleport |= change instanceof ClientboundPlayerPositionPacket;
                    if (setsVelocity(change, chain.player.player())) {
                        chain.carriedVelocityUnknown = chain.entityVelocityUnknown = false;
                        chain.velocityGaps.clear();
                    }
                    chain.player.applyStep(seq, target(world.world()));
                    next[c]++;
                }
                if (teleport && world.world() != null)
                    world.world().predictionTeleport();
            }
            syncPassengers();
        }
        world.apply(window, worldThrough);
        snapBack();
        syncPassengers();
        for (int c = 0; c < count; c++) chains.get(c).player.apply(window, through[c], target(world.world()));
        syncPassengers();
    }

    /**
     * Adds, from every state kept, the client states that handled the in-flight player changes up to one that moves the
     * player ({@link #movesPlayer}): the client handles packets in wire order and answers the marker after a change only
     * once it handled the change, so it may have used any such prefix. A prefix ends before a change that replaces or
     * places the player (login, respawn, teleports) and before a world change in flight that concerns the player (no state
     * applies world changes ahead of their marker).
     */
    private void branch(SyncWindow window) {
        long limit = window.possibleBefore();
        long[] worldSeqs = world.pendingSeqs(limit);
        var worldChanges = world.pendingBefore(limit);
        for (int i = 0; i < worldSeqs.length; i++) {
            if (concernsPlayer(worldChanges.get(i))) {
                limit = worldSeqs[i];
                break;
            }
        }
        int existing = chains.size();
        for (int c = 0; c < existing; c++) {
            Chain chain = chains.get(c);
            long[] seqs = chain.player.pendingSeqs(limit);
            var changes = chain.player.pendingBefore(limit);
            // the firework rocket changes in flight this state has not handled: a state may end its prefix at each
            long[] rockets = rocketForkPoints(chain.player.appliedThrough(), window.possibleBefore());
            int r = 0;
            for (int i = 0; ; ) {
                long player = i < seqs.length ? seqs[i] : Long.MAX_VALUE;
                long rocket = r < rockets.length && rockets[r] < limit ? rockets[r] : Long.MAX_VALUE;
                if (player == Long.MAX_VALUE && rocket == Long.MAX_VALUE)
                    break;
                if (chains.size() >= MAX_CHAINS || player < rocket && !forkable(changes.get(i)))
                    break;
                int applied = player < rocket ? i + 1 : i;
                if (player < rocket)
                    i++;
                else
                    r++;
                if (player < rocket && !movesPlayer(changes.get(i - 1)))
                    continue;
                if (player < rocket)
                    forkedPlayerChanges.add(player);
                Chain fork = chain.fork();
                for (int j = 0; j < applied; j++) {
                    var change = changes.get(j);
                    if (setsVelocity(change, fork.player.player())) {
                        fork.carriedVelocityUnknown = fork.entityVelocityUnknown = false;
                        fork.velocityGaps.clear();
                    }
                    fork.player.applyStep(seqs[j], target(world.world()));
                    if (movesPlayer(change))
                        fork.early.add(changeName(change));
                }
                if (rocket < player) {
                    // the world's rocket changes through it: this state's rockets differ from the model's world
                    fork.player.applyStep(rocket, target(world.world()));
                    fork.early.add("ракета");
                }
                chains.add(fork);
                syncPassenger(fork);
            }
            if (r < rockets.length)
                rocketForksIncomplete = true;
        }
    }

    /**
     * The world changes in flight (sequence in {@code (after, before)}) at which a client state's firework rockets differ
     * from the states that did not handle them: {@code set_entity_data} of a rocket naming the entity it is attached to,
     * the removal of a rocket. A state that handled the world through one of them has those rockets
     * ({@link #rocketsAhead}); the others do not: the next tick tells them apart.
     */
    private long[] rocketForkPoints(long after, long before) {
        CompensatedWorld level = world.world();
        var motions = level == null || level.entities() == null ? null : level.entities().motions();
        if (motions == null)
            return new long[0];
        int item = entityTypes.dataId("FireworkRocketEntity.DATA_ATTACHED_TO_TARGET");
        long[] seqs = world.pendingSeqs(before);
        var changes = world.pendingBefore(before);
        java.util.Set<Integer> spawned = new java.util.HashSet<>();
        List<Long> points = new ArrayList<>();
        for (int i = 0; i < seqs.length; i++) {
            switch(changes.get(i)) {
                case ClientboundAddEntityPacket spawn ->
                    {
                        if (Packets.type(spawn).equals(EntityMotions.ROCKET))
                            spawned.add(spawn.getId());
                        else
                            spawned.remove(spawn.getId());
                    }
                case ClientboundSetEntityDataPacket data ->
                    {
                        if ((spawned.contains(data.id()) || motions.rocket(data.id())) && item >= 0 && Packets.mobItems(data).containsKey(item) && seqs[i] > after)
                            points.add(seqs[i]);
                    }
                case ClientboundRemoveEntitiesPacket remove ->
                    {
                        boolean rocket = false;
                        for (int id : remove.entityIds()) rocket |= spawned.remove(id) || motions.rocket(id);
                        if (rocket && seqs[i] > after)
                            points.add(seqs[i]);
                    }
                default ->
                    {
                    }
            }
        }
        return points.stream().mapToLong(Long::longValue).toArray();
    }

    /**
     * The firework rockets of a client state that handled the world's changes through {@code through}, where this model's
     * world has not applied them yet: the rockets it removed, the {@code DATA_ATTACHED_TO_TARGET} it last set for a
     * rocket (by id), and the rockets it spawned ({@code add_entity}).
     */
    private record RocketsAhead(java.util.Set<Integer> removed, java.util.Map<Integer, java.util.OptionalInt> attachments, java.util.Map<Integer, ClientboundAddEntityPacket> spawned) {

        static final RocketsAhead NONE = new RocketsAhead(java.util.Set.of(), java.util.Map.of(), java.util.Map.of());

        /**
         * A rocket of the model's world this state no longer has attached: removed, or its attachment cleared.
         */
        boolean detached(int id) {
            return removed.contains(id) || attachments.containsKey(id) && attachments.get(id).isEmpty();
        }
    }

    private RocketsAhead rocketsAhead(long through) {
        CompensatedWorld level = world.world();
        var entities = level == null ? null : level.entities();
        if (entities == null || through <= world.appliedThrough())
            return RocketsAhead.NONE;
        var motions = entities.motions();
        int item = entityTypes.dataId("FireworkRocketEntity.DATA_ATTACHED_TO_TARGET");
        java.util.Set<Integer> removed = new java.util.HashSet<>();
        var spawned = new java.util.LinkedHashMap<Integer, ClientboundAddEntityPacket>();
        var attachments = new java.util.HashMap<Integer, java.util.OptionalInt>();
        for (var change : world.pendingBefore(through + 1)) {
            switch(change) {
                case ClientboundAddEntityPacket spawn ->
                    {
                        spawned.remove(spawn.getId());
                        attachments.remove(spawn.getId());
                        if (Packets.type(spawn).equals(EntityMotions.ROCKET))
                            spawned.put(spawn.getId(), spawn);
                    }
                case ClientboundSetEntityDataPacket data ->
                    {
                        boolean rocket = spawned.containsKey(data.id()) || motions != null && motions.rocket(data.id()) && !removed.contains(data.id());
                        if (rocket && Packets.mobItems(data).get(item) instanceof java.util.OptionalInt target) {
                            attachments.put(data.id(), target);
                        }
                    }
                case ClientboundRemoveEntitiesPacket remove ->
                    {
                        for (int id : remove.entityIds()) {
                            spawned.remove(id);
                            attachments.remove(id);
                            removed.add(id);
                        }
                    }
                default ->
                    {
                    }
            }
        }
        return new RocketsAhead(removed, attachments, spawned);
    }

    /**
     * The ticks of the rockets a state handled ahead of this model's world, after the entities the model ticked: the ones it
     * spawned (the newest entities of its tick list) and the model's rockets its first tick finds attached to the player
     * only in this state; each pushes the player while it glides.
     */
    private void rocketsAhead(CompensatedWorld level) {
        var motions = level.entities() == null ? null : level.entities().motions();
        if (motions == null)
            return;
        int me = level.entities().localPlayerId();
        for (Chain chain : chains) {
            var ahead = rocketsAhead(chain.player.appliedThrough());
            PlayerState p = chain.player.player();
            long playerChunk = EntityMotions.chunkKeyOf((int) Math.floor(p.x()) >> 4, (int) Math.floor(p.z()) >> 4);
            ahead.attachments().forEach((id, target) -> {
                if (target.isEmpty() || target.getAsInt() != me || ahead.removed().contains(id))
                    return;
                boolean ticking;
                var spawn = ahead.spawned().get(id);
                if (spawn != null) {
                    // added where the server spawned it, it then moves with the player
                    var at = new net.minecraft.world.phys.Vec3(spawn.getX(), spawn.getY(), spawn.getZ());
                    ticking = level.tickingChunk(EntityMotions.chunkKeyOf((int) Math.floor(at.x()) >> 4, (int) Math.floor(at.z()) >> 4));
                    if (ticking != level.tickingChunk(playerChunk))
                        pushGapsAfter.add(Unmodeled.ENTITY_PUSH);
                } else if (motions.rocket(id) && motions.rocketUnresolved(id)) {
                    ticking = motions.rocketTicking(id);
                } else {
                    return;
                }
                if (ticking && p.fallFlying)
                    p.deltaMovement = fireworks.boost(p.deltaMovement, p.yRot, p.xRot);
            });
        }
    }

    /**
     * A sleep ({@code set_entity_data} with a sleeping position: {@code setPosToBed}) or a wake-up ({@code animate}:
     * {@code stopSleeping} stands the player up) that the client may have handled before the last tick and that a state
     * applies only now, where no state was branched to have handled it then: had the client taken it then, its last report
     * already holds the move, which the model now makes again (SLEEP_TIMING for this tick).
     */
    private void sleepsLate(SyncWindow window) {
        for (Chain chain : chains) {
            long[] seqs = chain.player.pendingSeqs(lastPossibleBefore);
            var changes = chain.player.pendingBefore(lastPossibleBefore);
            for (int i = 0; i < seqs.length; i++) {
                if (seqs[i] > window.certainThrough() || forkedPlayerChanges.contains(seqs[i]))
                    continue;
                boolean sleep = changes.get(i) instanceof ClientboundSetEntityDataPacket data && EntityDataItems.of(data).containsKey(EntityDataItems.SLEEPING_POS);
                if (sleep || changes.get(i) instanceof ClientboundAnimatePacket) {
                    syncGaps.add(Unmodeled.SLEEP_TIMING);
                }
            }
        }
        forkedPlayerChanges.removeIf(seq -> seq <= window.certainThrough());
    }

    /**
     * Whether a block change still in flight leaves the client's movement as it is: the same state, or a bed whose only
     * change is {@code occupied} (it flips as a player lies down or gets up; {@code BedBlock}/{@code AbstractBedBlock} read
     * it only in the server's use and in the other half's {@code updateShape}, never in a shape, a fall or a bounce).
     */
    private boolean movementIrrelevant(CompensatedWorld level, int x, int y, int z, int state) {
        int now = level.getBlock(x, y, z);
        if (now == state)
            return true;
        if (now == CompensatedWorld.UNLOADED || state < 0 || state >= registry.stateCount())
            return false;
        var from = registry.block(now);
        var to = registry.block(state);
        if (!from.name().equals(to.name()) || !(from.isInstance("BedBlock") || from.isInstance("AbstractBedBlock")))
            return false;
        return withoutOccupied(registry.properties(now)).equals(withoutOccupied(registry.properties(state)));
    }

    private static String withoutOccupied(String properties) {
        return java.util.Arrays.stream(properties.split(",")).filter(p -> !p.startsWith("occupied=")).toList().toString();
    }

    /**
     * A change a state may handle ahead of its marker: not one that replaces the player or places it.
     */
    private static boolean forkable(Object change) {
        return !(change instanceof ClientboundLoginPacket || change instanceof ClientboundRespawnPacket || change instanceof ClientboundPlayerPositionPacket || change instanceof ClientboundPlayerRotationPacket || change instanceof ClientboundTeleportEntityPacket);
    }

    /**
     * A player change that changes how the player moves: effects, attributes, velocity, knockback, abilities and game
     * mode, its synced flags, pose and health, food, the end of an item use. Inventory changes are not branched on (they
     * stay reported in flight).
     */
    private static boolean movesPlayer(Object change) {
        return changeName(change) != null;
    }

    /**
     * The name a player change that changes how the player moves has in the notes of the states that handled it early
     * ({@link #movesPlayer}), {@code null} for any other change. The names are those of the recording schema.
     */
    private static String changeName(Object change) {
        return switch(change) {
            case ClientboundUpdateMobEffectPacket ignored ->
                "EffectUpdate";
            case ClientboundRemoveMobEffectPacket ignored ->
                "EffectRemove";
            case ClientboundUpdateAttributesPacket ignored ->
                "EntityAttributes";
            case ClientboundSetEntityMotionPacket ignored ->
                "EntityVelocity";
            case ClientboundExplodePacket ignored ->
                "Explosion";
            case ClientboundPlayerAbilitiesPacket ignored ->
                "Abilities";
            case ClientboundGameEventPacket ignored ->
                "GameEvent";
            case ClientboundPlayerInfoUpdatePacket ignored ->
                "PlayerInfoGameMode";
            case ClientboundSetEntityDataPacket ignored ->
                "EntitySyncedData";
            case ClientboundSetHealthPacket ignored ->
                "PlayerHealth";
            case ClientboundEntityEventPacket ignored ->
                "EntityEvent";
            // a wake-up stands the player up
            case ClientboundAnimatePacket ignored ->
                "EntityAnimation";
            default ->
                null;
        };
    }

    /**
     * The client's block predictions of a packet ({@code MultiPlayerGameMode.startPrediction}): the action runs in the
     * client's world before the packet is sent, inside the tick's key handling (before the player moves). Breaking
     * ({@code destroyBlock}) is followed exactly for {@code STOP_DESTROY_BLOCK} and a creative {@code START_DESTROY_BLOCK}
     * ({@link ru.minecomplex.mac.player.ClientBlockBreaking}); a survival start breaks at once only when the destroy
     * progress reaches 1, which the model does not compute, so the block is uncertain until the acknowledgement. Using an
     * item on a resolved door/trapdoor/gate runs its local opening and neighbour callbacks before movement. Consuming
     * server-only containers leave the client blocks unchanged. Other block/item effects (placing, buckets …) stay
     * uncertain until the acknowledgement.
     */
    private void predict(Object packet) {
        CompensatedWorld level = world.world();
        if (level == null)
            return;
        PlayerState p = lead().player();
        ClientPlayerData data = lead().data();
        switch(packet) {
            case ServerboundPlayerActionPacket action when action.getAction() == net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK || action.getAction() == net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK ->
                {
                    var at = action.getPos();
                    level.startPrediction(action.getSequence(), p.position);
                    int state = level.getBlock(at.getX(), at.getY(), at.getZ());
                    boolean air = state == CompensatedWorld.UNLOADED || registry.isAir(state);
                    if (action.getAction() == net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK && !p.abilities.instabuild) {
                        // startDestroyBlock without instabuild: destroyBlock only when getDestroyProgress >= 1
                        if (!air)
                            level.predictionUncertain();
                    } else {
                        var outcome = breaking(air ? null : registry.block(state), air);
                        if (outcome == ru.minecomplex.mac.player.ClientBlockBreaking.Outcome.BREAKS || outcome == ru.minecomplex.mac.player.ClientBlockBreaking.Outcome.BREAKS_UNCERTAIN) {
                            level.destroyBlock(at.getX(), at.getY(), at.getZ());
                        }
                        if (outcome == ru.minecomplex.mac.player.ClientBlockBreaking.Outcome.BREAKS_UNCERTAIN || outcome == ru.minecomplex.mac.player.ClientBlockBreaking.Outcome.UNKNOWN) {
                            level.predictionUncertain();
                        }
                    }
                    level.endPrediction();
                }
            case ServerboundUseItemOnPacket use ->
                {
                    var at = use.hitResult().getBlockPos();
                    level.startPrediction(use.sequence(), p.position);
                    // performUseItemOn changes nothing for a spectator
                    if (data.localMode != net.minecraft.world.level.GameType.SPECTATOR) {
                        int state = level.getBlock(at.getX(), at.getY(), at.getZ());
                        boolean consumed = chains.stream().allMatch(chain -> ru.minecomplex.mac.world.OpenableBlocks.consumes(registry, state, chain.player.player(), chain.player.data(), use));
                        if (consumed) {
                            level.useOpenable(at.getX(), at.getY(), at.getZ(), p.yRot);
                            if (registry.block(state).isInstance("FenceGateBlock") && chains.stream().anyMatch(chain -> Float.floatToIntBits(chain.player.player().yRot) != Float.floatToIntBits(p.yRot)))
                                level.predictionUncertain();
                        } else
                            level.predictionUncertain();
                    }
                    level.endPrediction();
                }
            case ServerboundUseItemPacket use ->
                {
                    level.startPrediction(use.sequence(), p.position);
                    var inventory = data.inventory;
                    int slot = inventory.handSlot(use.hand() == net.minecraft.world.InteractionHand.OFF_HAND);
                    if (slot < 0 || inventory.unknown(slot) || !inventory.item(slot).isEmpty() && items.use(Stacks.key(inventory.item(slot))) == ru.minecomplex.mac.inventory.item.UseKind.AIMED_TRANSFORM) {
                        level.predictionUncertain();
                    }
                    level.endPrediction();
                }
            default ->
                {
                }
        }
    }

    /**
     * {@code destroyBlock}'s decision, the same in every player state kept or unknown.
     */
    private ru.minecomplex.mac.player.ClientBlockBreaking.Outcome breaking(ru.minecomplex.mac.world.BlockType block, boolean air) {
        ru.minecomplex.mac.player.ClientBlockBreaking.Outcome outcome = null;
        for (Chain chain : chains) {
            var one = ru.minecomplex.mac.player.ClientBlockBreaking.destroyBlock(items, chain.player.player(), chain.player.data(), block, air);
            if (outcome != null && one != outcome)
                return ru.minecomplex.mac.player.ClientBlockBreaking.Outcome.UNKNOWN;
            outcome = one;
        }
        return outcome;
    }

    /**
     * {@code ClientLevel.syncBlockState} after an acknowledgement: a player colliding with a block set back is put where
     * it stood when that block was predicted ({@code absSnapTo}). Vanilla ends predictions in hash order; with two such
     * blocks the order is not modelled.
     */
    private void snapBack() {
        CompensatedWorld level = world.world();
        if (level == null)
            return;
        var restored = level.takeRestored();
        if (restored.isEmpty())
            return;
        int me = level.entities() == null ? -1 : level.entities().localPlayerId();
        for (Chain chain : chains) {
            PlayerState p = chain.player.player();
            int snaps = 0;
            for (var block : restored) {
                var physics = new MovementTickerPlayer(environment(level, () -> EntityMotions.Collider.player(me, p.position), syncGaps, null), p, chain.player.local(), chain.player.data());
                if (!physics.isColliding(block.pos().x(), block.pos().y(), block.pos().z(), block.state()))
                    continue;
                Vec3d at = block.playerPos();
                // Entity.absSnapTo: x and z clamped to 3e7, old position too
                Vec3d to = new Vec3d(Math.clamp(at.x(), -3.0E7, 3.0E7), at.y(), Math.clamp(at.z(), -3.0E7, 3.0E7));
                p.position = to;
                p.oldPosition = to;
                snaps++;
            }
            if (snaps > 1)
                syncGaps.add(Unmodeled.BLOCK_PREDICTION);
        }
    }

    /**
     * While riding only the lead is followed: the vehicle probe keeps one ride.
     */
    private void retireAlternatives() {
        while (chains.size() > 1) chains.remove(chains.size() - 1).player.close();
    }

    /**
     * Keeps the states whose tick came closest to the client's (all of them when none was compared) and, of those that
     * applied the same changes, the oldest: they told the same so far. The first one left reports the tick.
     */
    private TickReport select(TickReport[] reports) {
        double best = Double.POSITIVE_INFINITY;
        for (TickReport report : reports) {
            if (report.status() == Status.COMPARED && report.offset() < best)
                best = report.offset();
        }
        boolean compared = best != Double.POSITIVE_INFINITY;
        Chain previous = chains.get(0);
        TickReport chosen = null;
        List<Chain> kept = new ArrayList<>();
        record History(long appliedThrough, Object actions, boolean unannouncedSlot, Vec3d velocity, boolean sprinting, ru.minecomplex.mac.entity.Pose pose, int sprintTrigger, int jumpTrigger, int autoJump, ru.minecomplex.mac.util.math.Vec2f vector) {
        }
        var applied = new java.util.HashMap<History, List<Chain>>();
        for (int c = 0; c < chains.size(); c++) {
            Chain chain = chains.get(c);
            var p = chain.player.player();
            var local = chain.player.local();
            var history = new History(chain.player.appliedThrough(), chain.player.actionHistory(), chain.unannouncedSlot, p.deltaMovement, p.sprinting, p.pose, local.sprintTriggerTime, local.jumpTriggerTime, local.autoJumpTime, local.vector);
            boolean merged = false;
            if (compared && reports[c].offset() == best) {
                var equivalents = applied.computeIfAbsent(history, ignored -> new ArrayList<>());
                var data = chain.player.data();
                for (Chain equivalent : equivalents) {
                    var retained = equivalent.player.data();
                    // Exactly represent a union of overlapping/adjacent attack ages. Never fill a hole between ages.
                    if (data.attackTicksMin <= retained.attackTicksMax + 1 && retained.attackTicksMin <= data.attackTicksMax + 1) {
                        retained.attackTicksMin = Math.min(retained.attackTicksMin, data.attackTicksMin);
                        retained.attackTicksMax = Math.max(retained.attackTicksMax, data.attackTicksMax);
                        merged = true;
                        break;
                    }
                }
                if (!merged)
                    equivalents.add(chain);
            }
            if (!compared || reports[c].offset() == best && !merged) {
                kept.add(chain);
                if (chosen == null)
                    chosen = reports[c];
            } else {
                chain.player.close();
            }
        }
        chains.clear();
        chains.addAll(kept);
        Chain lead = chains.get(0);
        if (lead != previous) {
            if (!lead.early.isEmpty()) {
                String note = "клиент применил до ответа на маркер: " + String.join(", ", lead.early);
                chosen = new TickReport(chosen.frame(), chosen.status(), chosen.offset(), chosen.predicted(), chosen.reported(), chosen.inFlight(), chosen.gaps(), chosen.note().isEmpty() ? note : chosen.note() + "; " + note, chosen.vehicle());
            }
            lead.early.clear();
        }
        return chosen;
    }

    /**
     * The entities after the local player in this tick's list have ticked.
     */
    private boolean afterTicksDone;

    /**
     * {@code ClientLevel.tickEntities} after the local player: their pushes reach its velocity (reported with the next tick),
     * a shulker's opening moves it at once.
     */
    private void tickEntitiesAfter(CompensatedWorld level) {
        afterTicksDone = true;
        pushGapsAfter.clear();
        level.tickEntities(false, bodyQueries(level), pushTarget(level), pushGapsAfter, fluidPush(level), climbable(level));
        rocketsAhead(level);
        var motions = level.entities() == null ? null : level.entities().motions();
        pushedAfter = false;
        if (motions == null)
            return;
        for (Chain chain : chains) {
            PlayerState p = chain.player.player();
            if (!p.passenger && motions.mayPush(p.position, p.boundingBox()))
                pushedAfter = true;
        }
    }

    /**
     * The block entities have ticked in this frame.
     */
    private boolean blockEntitiesDone;

    /**
     * {@code level.tickBlockEntities()} after the entity ticks (1.21.4: the end of {@code ClientLevel.tickEntities}): pistons
     * and shulker boxes advance in their creation order, and what they sweep moves ({@code moveCollidedEntities}: an
     * opening shulker box by {@code Entity.move(SHULKER_BOX)}, a piston by {@code PistonMovingBlockEntity
     * .moveEntityByPiston}). {@code level.getEntities(null, area)} lists no spectator; every player state kept is moved on
     * its own, the other simulated entities swept become unknown ({@link EntityMotions#displacedBy}).
     *
     * @param gaps collects what the moves of the player could not resolve
     */
    private void tickBlockEntities(CompensatedWorld level, Set<Unmodeled> gaps) {
        blockEntitiesDone = true;
        var motions = level.entities() == null ? null : level.entities().motions();
        int me = level.entities() == null ? -1 : level.entities().localPlayerId();
        Long time = level.gameTime();
        List<MovementTickerPlayer> physics = new ArrayList<>();
        for (Chain chain : chains) {
            PlayerState p = chain.player.player();
            physics.add(new MovementTickerPlayer(environment(level, () -> EntityMotions.Collider.player(me, p.position), gaps, null), p, chain.player.local(), chain.player.data()));
        }
        ru.minecomplex.mac.world.shulker.ShulkerPush.Query shulkers = area -> {
            if (motions != null && motions.displacedBy(area))
                gaps.add(Unmodeled.RIDING);
            List<ru.minecomplex.mac.world.shulker.ShulkerPush.Entity> listed = new ArrayList<>();
            for (int c = 0; c < chains.size(); c++) {
                PlayerState p = chains.get(c).player.player();
                if (!p.spectator && EntityMotions.listed(area, p.position, p.boundingBox()))
                    listed.add(physics.get(c).shulkerEntity());
            }
            return listed;
        };
        // a piston moves the player within the level's game time (limitPistonMovement); before set_time it is not known
        ru.minecomplex.mac.world.piston.PistonPush push = time == null ? null : new ru.minecomplex.mac.world.piston.PistonPush(registry, ru.minecomplex.mac.mechanics.collision.BlockCollisions.emptyContext(registry, level, gaps::add));
        ru.minecomplex.mac.world.piston.PistonPush.Query pistons = time == null ? null : area -> {
            if (motions != null && motions.displacedBy(area))
                gaps.add(Unmodeled.RIDING);
            List<ru.minecomplex.mac.world.piston.PistonPush.Entity> listed = new ArrayList<>();
            for (int c = 0; c < chains.size(); c++) {
                PlayerState p = chains.get(c).player.player();
                if (!EntityMotions.listed(area, p.position, p.boundingBox()))
                    continue;
                // the moving area lists no spectator, a honey block's sticky one does: the query does not say which it is
                if (p.spectator)
                    gaps.add(Unmodeled.PISTON_ENTITIES);
                else
                    listed.add(physics.get(c).pistonEntity(time));
            }
            return listed;
        };
        Integer root = level.entities() == null ? null : level.entities().passengers().root(me);
        ru.minecomplex.mac.engine.blockeffects.GeyserLaunch.Query geysers = area -> {
            List<ru.minecomplex.mac.engine.blockeffects.GeyserLaunch.Entity> listed = new ArrayList<>();
            // the own vehicle the client simulates is lifted too: the ride's body of this frame, while one state rides it
            Tri vehicle = motions == null ? Tri.FALSE : motions.ownedIn(area);
            if (vehicle != Tri.FALSE) {
                PlayerState body = vehicles.simulatedBody();
                if (vehicle == Tri.EITHER || body == null || chains.size() != 1 || root == null || root == me) {
                    gaps.add(Unmodeled.GEYSER);
                } else {
                    listed.add(new ru.minecomplex.mac.engine.blockeffects.GeyserLaunch.Entity() {

                        @Override
                        public Vec3d velocity() {
                            return body.deltaMovement;
                        }

                        @Override
                        public void velocity(Vec3d value) {
                            vehicles.pushed(value);
                            motions.ownedVelocity(root, value);
                        }

                        @Override
                        public double fallDistance() {
                            return body.fallDistance;
                        }

                        @Override
                        public void fallDistance(double value) {
                            body.fallDistance = value;
                        }

                        // a root vehicle rides nothing; a boat or mount is no player and not in #not_affected_by_geysers
                        @Override
                        public boolean launchable() {
                            return true;
                        }
                    });
                }
            }
            for (Chain chain : chains) {
                PlayerState p = chain.player.player();
                if (p.spectator || !p.alive || !EntityMotions.listed(area, p.position, p.boundingBox()))
                    continue;
                listed.add(new ru.minecomplex.mac.engine.blockeffects.GeyserLaunch.Entity() {

                    @Override
                    public Vec3d velocity() {
                        return p.deltaMovement;
                    }

                    @Override
                    public void velocity(Vec3d value) {
                        p.deltaMovement = value;
                    }

                    @Override
                    public double fallDistance() {
                        return p.fallDistance;
                    }

                    @Override
                    public void fallDistance(double value) {
                        p.fallDistance = value;
                    }

                    // the local player simulates its movement; a player is not in #not_affected_by_geysers (vanilla tag)
                    @Override
                    public boolean launchable() {
                        return !p.abilities.flying && !p.passenger;
                    }
                });
            }
            return listed;
        };
        // a physics copies the level's gaps when created: the tick reports those itself
        Set<Unmodeled> level0 = EnumSet.noneOf(Unmodeled.class);
        for (MovementTickerPlayer one : physics) level0.addAll(one.unmodeled());
        level.tickBlockEntities(time == null ? 0L : time, push, pistons, shulkers, geysers);
        for (MovementTickerPlayer one : physics) {
            for (Unmodeled gap : one.unmodeled()) if (!level0.contains(gap))
                gaps.add(gap);
        }
    }

    private static TickReport withGaps(TickReport report, Set<Unmodeled> more) {
        Set<Unmodeled> gaps = EnumSet.noneOf(Unmodeled.class);
        gaps.addAll(report.gaps());
        gaps.addAll(more);
        return new TickReport(report.frame(), report.status(), report.offset(), report.predicted(), report.reported(), report.inFlight(), gaps, report.note(), report.vehicle());
    }

    /**
     * One state's tick between the phases of {@link #move}.
     */
    private static final class Step {

        final int index;

        final Chain chain;

        final List<String> windowOpen;

        final Set<Unmodeled> gaps;

        final Set<Unmodeled> queryGaps = EnumSet.noneOf(Unmodeled.class);

        String carriedNote = "";

        /**
         * The velocity this tick starts from is uncertain from an earlier entity gap ({@link Chain#entityVelocityUnknown}).
         */
        boolean carriedEntity;

        /**
         * ... from the gaps of earlier ticks ({@link Chain#velocityGaps}).
         */
        final EnumSet<Unmodeled> carriedVelocity = EnumSet.noneOf(Unmodeled.class);

        MovementTickerPlayer physics;

        /**
         * What the tick could not simulate; its packets are {@code physics.packets()}.
         */
        Set<Unmodeled> tickGaps;

        Step(int index, Chain chain, List<String> windowOpen, Set<Unmodeled> gaps) {
            this.index = index;
            this.chain = chain;
            this.windowOpen = windowOpen;
            this.gaps = gaps;
        }
    }

    /**
     * The LocalPlayer's movement of the tick in every state kept, compared with what the client reported.
     */
    private TickReport[] move(ClientTickFrame frame, SyncWindow window) {
        CompensatedWorld level = world.world();
        ServerboundMovePlayerPacket reported = frame.movement();
        TickReport[] reports = new TickReport[chains.size()];
        List<String> worldInFlight = new ArrayList<>();
        entityInFlight = false;
        var inFlightMotions = level == null || level.entities() == null ? null : level.entities().motions();
        for (var change : world.pendingBefore(window.possibleBefore())) {
            if (!concernsPlayer(change))
                continue;
            worldInFlight.add(change.getClass().getSimpleName());
            entityInFlight |= mayMoveEntity(change, inFlightMotions);
        }
        entityInFlight |= rocketForksIncomplete;
        List<Step> steps = new ArrayList<>();
        for (int c = 0; c < chains.size(); c++) {
            Chain chain = chains.get(c);
            List<String> windowOpen = new ArrayList<>();
            for (var change : chain.player.pendingBefore(window.possibleBefore())) {
                if (concernsPlayerModel(change))
                    windowOpen.add(change.getClass().getSimpleName());
            }
            windowOpen.addAll(worldInFlight);
            Set<Unmodeled> gaps = EnumSet.noneOf(Unmodeled.class);
            gaps.addAll(chain.player.gaps());
            gaps.addAll(syncGaps);
            if (chain.unannouncedSlot)
                gaps.add(Unmodeled.ITEM_USE_TIMING);
            if (level == null) {
                reports[c] = skip(frame, windowOpen, gaps, "мира ещё нет");
                continue;
            }
            gaps.addAll(level.dynamicGaps());
            if (gaps.contains(Unmodeled.RESPAWN_POSITION))
                reports[c] = skip(frame, windowOpen, gaps, "ждём телепорт на спавн");
            else if (gaps.contains(Unmodeled.BED_STAND_UP))
                reports[c] = skip(frame, windowOpen, gaps, "ждём телепорт после вставания с кровати");
            else if (!chain.player.data().clientLoaded)
                reports[c] = skip(frame, windowOpen, gaps, "клиент загружает мир");
            else
                steps.add(new Step(c, chain, windowOpen, gaps));
        }
        if (steps.isEmpty())
            return reports;
        var motions = level.entities() == null ? null : level.entities().motions();
        for (Step step : steps) {
            if (pushedAfter)
                step.gaps.add(Unmodeled.ENTITY_PUSH);
            step.gaps.addAll(pushGapsAfter);
            step.gaps.addAll(pushGapsBefore);
            step.gaps.addAll(blockGapsAfter);
            PlayerState p = step.chain.player.player();
            if (reported != null && reported.hasRotation()) {
                p.yRot = reported.getYRot(0);
                p.xRot = reported.getXRot(0);
            }
        }
        pushedAfter = false;
        Step lead = steps.get(0);
        if (lead.chain.player.player().passenger) {
            // riding: the alternatives were retired with the frame's certain changes
            reports[lead.index] = ride(frame, lead, level, motions);
            return reports;
        }
        vehicles.reset();
        int me = level.entities() == null ? -1 : level.entities().localPlayerId();
        var keys = ru.minecomplex.mac.player.state.PlayerInput.fromMask(frame.input() & 127);
        for (Step step : steps) {
            Chain chain = step.chain;
            PlayerState p = chain.player.player();
            if (chain.carriedVelocityUnknown) {
                step.gaps.add(Unmodeled.RIDING);
                step.carriedNote = "скорость, оставленная поездкой, неизвестна";
            }
            step.carriedEntity = chain.entityVelocityUnknown;
            step.carriedVelocity.addAll(chain.velocityGaps);
            if (motions != null && motions.mayPush(p.position, p.boundingBox()))
                step.gaps.add(Unmodeled.ENTITY_PUSH);
            step.physics = new MovementTickerPlayer(environment(level, () -> EntityMotions.Collider.player(me, p.position), step.queryGaps, null), p, chain.player.local(), chain.player.data());
            // an own interpolation step sets the rotation the client then sent
            if (reported != null && reported.hasRotation())
                step.physics.rotationAfterTick(reported.getYRot(0), reported.getXRot(0));
        }
        {
            // 26.3 sends after every entity ticked: what those after the player did to its position is in the packet
            for (Step step : steps) step.physics.simulateTick(keys);
            tickEntitiesAfter(level);
            Set<Unmodeled> blockGaps = EnumSet.noneOf(Unmodeled.class);
            tickBlockEntities(level, blockGaps);
            for (Step step : steps) {
                step.gaps.addAll(blockGaps);
                step.tickGaps = step.physics.sendAfterEntityTicks();
                step.gaps.addAll(pushGapsAfter);
            }
        }
        Vec3d previousReported = lastReported;
        for (Step step : steps) reports[step.index] = compare(frame, step, motions, previousReported);
        if (reported != null && reported.hasPosition())
            lastReported = new Vec3d(reported.getX(0), reported.getY(0), reported.getZ(0));
        return reports;
    }

    /**
     * The root vehicle's tick while the player rides, compared with the vehicle's {@code move_vehicle}.
     */
    private TickReport ride(ClientTickFrame frame, Step step, CompensatedWorld level, EntityMotions motions) {
        Chain chain = step.chain;
        PlayerState p = chain.player.player();
        LocalPlayerState local = chain.player.local();
        ClientPlayerData data = chain.player.data();
        ServerboundMovePlayerPacket reported = frame.movement();
        var entities = level.entities();
        Integer rootId = entities == null ? null : entities.passengers().root(entities.localPlayerId());
        int vehicleId = rootId == null ? -1 : rootId;
        String vehicleType = rootId == null ? null : entities.typeOf(rootId);
        // the vehicle's own movement queries as the vehicle, the passenger's own tick (its travel in the seat) as the player
        boolean[] riderMoving = { false };
        int me = entities == null ? -1 : entities.localPlayerId();
        MovementEnvironment vehicleEnv = environment(level, () -> motions == null ? null : riderMoving[0] ? EntityMotions.Collider.player(me, p.position) : motions.vehicleCollider(vehicleId, vehicleType, vehicles.vehiclePosition()), step.queryGaps, null);
        VehicleCheckRunner.Outcome ride = vehicles.tick(frame, level, vehicleEnv, p, local, data, box -> motions != null && motions.othersNear(vehicleId, box), moving -> riderMoving[0] = moving);
        ride = vehicles.borderCarried(ride, step.queryGaps);
        if (!vehicles.riderSimulated() && ride.status() == Status.SKIPPED)
            vehicles.tickUncontrolledPassenger(frame, level, vehicleEnv, p, local, data, step.gaps, moving -> riderMoving[0] = moving);
        chain.carriedVelocityUnknown = !vehicles.riderSimulated();
        chain.entityVelocityUnknown = false;
        chain.velocityGaps.clear();
        step.gaps.addAll(ride.gaps());
        step.gaps.addAll(step.queryGaps);
        // the client's own vehicle keeps the transform it reported when the player stops controlling it
        ServerboundMoveVehiclePacket sentVehicle = null;
        for (Object packet : frame.packets()) if (packet instanceof ServerboundMoveVehiclePacket move)
            sentVehicle = move;
        VehicleCheckRunner.RideTransform followed = vehicles.rideTransform();
        if (rootId != null && sentVehicle != null && followed != null) {
            // a living mount the ride followed: its transform (the client reports an interpolation target instead while
            // it interpolates) and interpolation
            Vec3d knownVelocity = vehicles.rideVelocity();
            Vec3d estimatedVelocity = knownVelocity != null ? knownVelocity : vehicles.rideVelocityEstimate();
            level.rideTransform(rootId, followed.position(), followed.yaw(), followed.pitch(), estimatedVelocity, knownVelocity != null, followed.interpolation(), followed.exact());
            entities.motions().rideOnGround(rootId, followed.onGround());
        } else if (rootId != null) {
            level.rideTransform(rootId, sentVehicle == null ? null : new Vec3d(sentVehicle.movingTo().position().x(), sentVehicle.movingTo().position().y(), sentVehicle.movingTo().position().z()), sentVehicle == null ? 0.0F : sentVehicle.movingTo().yRot(), sentVehicle == null ? 0.0F : sentVehicle.movingTo().xRot(), sentVehicle == null ? null : vehicles.rideVelocity());
        }
        if (reported != null && reported.hasRotation()) {
            local.send.yRotLast = reported.getYRot(0);
            local.send.xRotLast = reported.getXRot(0);
        }
        adoptSprint(frame, p, local);
        return new TickReport(frame.index(), ride.status(), ride.offset(), ride.predicted(), ride.reported(), step.windowOpen, step.gaps, ride.note(), ride.vehicle());
    }

    /**
     * One state's simulated position against the client's; the state then continues from the client's position.
     *
     * @param previousReported the position the client reported last, before this tick
     */
    private TickReport compare(ClientTickFrame frame, Step step, EntityMotions motions, Vec3d previousReported) {
        Chain chain = step.chain;
        PlayerState p = chain.player.player();
        LocalPlayerState local = chain.player.local();
        ServerboundMovePlayerPacket reported = frame.movement();
        Set<Unmodeled> gaps = step.gaps;
        gaps.addAll(step.tickGaps);
        gaps.addAll(step.queryGaps);
        if (motions != null && motions.mayPush(p.position, p.boundingBox()))
            gaps.add(Unmodeled.ENTITY_PUSH);
        Vec3d predicted = p.position;
        ServerboundMovePlayerPacket sent = null;
        for (Object packet : step.physics.packets()) {
            if (packet instanceof ServerboundMovePlayerPacket move)
                sent = move;
        }
        double offset;
        Vec3d reportedPosition = null;
        if (reported != null && reported.hasPosition()) {
            reportedPosition = new Vec3d(reported.getX(0), reported.getY(0), reported.getZ(0));
            // standing on the ground without movement keys and without moving: the velocity is the probe's again (its
            // horizontal parts are zero, the vertical one the gravity step after the landing collision)
            if (chain.carriedVelocityUnknown && reported.isOnGround() && reportedPosition.equals(previousReported) && (frame.input() & 31) == 0) {
                chain.carriedVelocityUnknown = false;
            }
            if (reported.isOnGround() && reportedPosition.equals(previousReported) && (frame.input() & 31) == 0) {
                chain.entityVelocityUnknown = false;
                chain.velocityGaps.clear();
            }
            offset = distance(predicted, reportedPosition);
            // the same movement as the client's, bit for bit, from the same position: the velocity it moved with is the
            // client's (an axis a collision stopped is zeroed on both sides) unless the movement was clamped without the
            // velocity (backing off an edge while sneaking) or a bouncy block turned the landing velocity around
            if ((chain.entityVelocityUnknown || !chain.velocityGaps.isEmpty()) && offset == 0.0 && (frame.input() & 32) == 0 && !bouncyBelow(p, predicted)) {
                chain.entityVelocityUnknown = false;
                chain.velocityGaps.clear();
            }
            // continue from the client's position: every tick is measured on its own
            p.position = reportedPosition;
            p.onGround = reported.isOnGround();
            local.send.xLast = reported.getX(0);
            local.send.yLast = reported.getY(0);
            local.send.zLast = reported.getZ(0);
            local.send.positionReminder = 0;
            local.send.lastOnGround = reported.isOnGround();
            local.send.lastHorizontalCollision = reported.horizontalCollision();
        } else {
            // the client sent no position: it moved at most 2e-4 from the last one it sent
            Vec3d last = new Vec3d(local.send.xLast, local.send.yLast, local.send.zLast);
            offset = Math.max(0.0, distance(predicted, previousReported != null ? previousReported : last) - 2.0E-4);
            if (sent != null && sent.hasPosition()) {
                local.send.xLast = last.x();
                local.send.yLast = last.y();
                local.send.zLast = last.z();
            }
        }
        if (reported != null && reported.hasRotation()) {
            local.send.yRotLast = reported.getYRot(0);
            local.send.xRotLast = reported.getXRot(0);
        }
        adoptSprint(frame, p, local);
        // other entities this tick was uncertain about (collisions, pushes, a change near the player the client may have
        // handled already, whose pushes reach the next tick): the velocity it leaves is a guess
        boolean ownEntityGap = gaps.contains(Unmodeled.ENTITY_PUSH) || gaps.contains(Unmodeled.ENTITY_COLLISION) || gaps.contains(Unmodeled.VEHICLE_ENTITIES) || entityInFlight;
        EnumSet<Unmodeled> ownVelocityGaps = EnumSet.noneOf(Unmodeled.class);
        for (Unmodeled gap : VELOCITY_GAPS) if (gaps.contains(gap))
            ownVelocityGaps.add(gap);
        String note = step.carriedNote;
        if (ownVelocityGaps.contains(Unmodeled.RIPTIDE) && !chain.velocityGaps.contains(Unmodeled.RIPTIDE)) {
            String cause = p.riptideUnknownCause.isEmpty() ? "неизвестен подъём рывка от земли" : p.riptideUnknownCause;
            chain.riptideSource = cause + " (первый тик " + frame.index() + ", позиция " + p.position + ")";
            note = note.isEmpty() ? chain.riptideSource : note + "; " + chain.riptideSource;
        }
        if (step.carriedEntity && chain.entityVelocityUnknown) {
            gaps.add(Unmodeled.ENTITY_PUSH);
            String carried = "скорость после неточного толчка или столкновения неизвестна";
            note = note.isEmpty() ? carried : note + "; " + carried;
        }
        if (ownEntityGap)
            chain.entityVelocityUnknown = true;
        // a border whose phase was not known near the player, a riptide the model could not decide: the velocity they left
        // is a guess as well
        for (Unmodeled gap : chain.velocityGaps) {
            if (!step.carriedVelocity.contains(gap) || !chain.velocityGaps.contains(gap))
                continue;
            gaps.add(gap);
            String carried = gap == Unmodeled.ITEM_USE_TIMING ? "скорость после неизвестной смены предмета неизвестна" : gap == Unmodeled.RIPTIDE ? "скорость после неизвестного рывка трезубца неизвестна" : "скорость после столкновения с границей мира неизвестной фазы неизвестна";
            if (gap == Unmodeled.RIPTIDE && !chain.riptideSource.isEmpty())
                carried += ": " + chain.riptideSource;
            note = note.isEmpty() ? carried : note + "; " + carried;
        }
        chain.velocityGaps.addAll(ownVelocityGaps);
        if (hotbarForksIncomplete)
            chain.velocityGaps.add(Unmodeled.ITEM_USE_TIMING);
        return new TickReport(frame.index(), Status.COMPARED, offset, predicted, reportedPosition, step.windowOpen, gaps, note, null);
    }

    /**
     * A block under the player's feet whose {@code updateEntityMovementAfterFallOn} keeps a part of the landing velocity
     * ({@code SlimeBlock}, {@code BedBlock}; every other block zeroes it): {@code getOnPosLegacy} of any column it covers.
     */
    private boolean bouncyBelow(PlayerState p, Vec3d at) {
        CompensatedWorld level = world.world();
        if (level == null)
            return true;
        Aabb box = p.boundingBox().move(at.x() - p.position.x(), at.y() - p.position.y(), at.z() - p.position.z());
        int y = (int) Math.floor(at.y() - 0.2F);
        for (int x = (int) Math.floor(box.minX()); x <= (int) Math.floor(box.maxX()); x++) {
            for (int z = (int) Math.floor(box.minZ()); z <= (int) Math.floor(box.maxZ()); z++) {
                String name = registry.block(level.getBlock(x, y, z)).name();
                if (name.equals("minecraft:slime_block") || name.endsWith("_bed"))
                    return true;
            }
        }
        return false;
    }

    /**
     * The sprint the client announced at the end of this tick (an attack may have stopped it): the next tick starts from
     * it, as from the reported position.
     */
    private static void adoptSprint(ClientTickFrame frame, PlayerState p, LocalPlayerState local) {
        for (Object packet : frame.packets()) {
            if (packet instanceof ServerboundPlayerCommandPacket command && (command.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING || command.getAction() == ServerboundPlayerCommandPacket.Action.STOP_SPRINTING)) {
                p.setSprinting(command.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING);
                local.send.wasSprinting = p.sprinting;
            }
        }
    }

    private TickReport skip(ClientTickFrame frame, List<String> windowOpen, Set<Unmodeled> gaps, String note) {
        return new TickReport(frame.index(), Status.SKIPPED, 0.0, null, null, windowOpen, gaps, note, null);
    }

    /**
     * {@code PlayerState.passenger} from the world's passenger graph. A mount ({@code Entity.startRiding}) stands the player
     * up ({@code setPose(STANDING)}, which refreshes the dimensions when the pose changes); a dismount
     * ({@code LocalPlayer.removeVehicle}) frees the hands. The position stays: the server teleports a dismounted player.
     */
    private void syncPassenger(Chain chain) {
        CompensatedWorld level = world.world();
        PlayerState p = chain.player.player();
        var entities = level == null ? null : level.entities();
        var own = entities == null ? null : entities.passengers().get(entities.localPlayerId());
        boolean riding = own != null && own.vehicle() != null;
        if (riding == p.passenger)
            return;
        if (riding) {
            if (p.pose != ru.minecomplex.mac.entity.Pose.STANDING) {
                p.pose = ru.minecomplex.mac.entity.Pose.STANDING;
                p.refreshDimensions();
            }
        } else {
            chain.player.local().handsBusy = false;
            chain.player.local().rootVehicle = null;
            vehicles.reset();
        }
        p.passenger = riding;
    }

    private void syncPassengers() {
        for (Chain chain : chains) syncPassenger(chain);
        var level = world.world();
        var entities = level == null ? null : level.entities();
        if (entities == null || entities.motions() == null)
            return;
        Integer root = entities.passengers().root(entities.localPlayerId());
        if (root != null && root != entities.localPlayerId())
            entities.motions().localController(root, vehicles.controller(entities, root, lead().data()));
    }

    /**
     * Whether a world change still in flight can matter for the player's tick: any change but entity ones, which count
     * for the player, its root vehicle and the entities followed near it (their transforms, riders and a happy ghast's
     * data decide collisions and pushes); the player's own data, attributes and effects are player changes, listed from
     * the player model.
     */
    private boolean concernsPlayer(Object change) {
        CompensatedWorld level = world.world();
        var entities = level == null ? null : level.entities();
        if (entities == null)
            return true;
        int me = entities.localPlayerId();
        Integer root = entities.passengers().root(me);
        boolean riding = root != null && root != me;
        java.util.function.IntPredicate vehicle = id -> riding && id == root;
        var motions = entities.motions();
        Aabb around = lead().player().boundingBox().inflate(NEAR);
        java.util.function.IntPredicate near = id -> motions != null && motions.near(id, around);
        java.util.function.IntPredicate ghast = id -> near.test(id) && motions.kindOf(id) == ru.minecomplex.mac.entity.PacketEntityFollowed.Kind.HAPPY_GHAST;
        // a living entity's data and attributes decide its box (age, pose, size, scale), a shulker's its face and peek
        java.util.function.IntPredicate shaped = id -> ghast.test(id) || near.test(id) && (motions.kindOf(id) == ru.minecomplex.mac.entity.PacketEntityFollowed.Kind.LIVING || motions.kindOf(id) == ru.minecomplex.mac.entity.PacketEntityFollowed.Kind.SHULKER);
        java.util.function.IntPredicate ours = id -> id == me || vehicle.test(id);
        // a firework rocket collides with and pushes nothing: its changes reach the player through the rocket's tick after
        // it, which the states that handled them ahead of this model follow (branch, rocketsAhead)
        java.util.function.IntPredicate rocket = id -> motions != null && motions.rocket(id) || pendingRocket(id);
        return switch(change) {
            case ClientboundAddEntityPacket spawn when Packets.type(spawn).equals(EntityMotions.ROCKET) ->
                false;
            case ClientboundSetEntityDataPacket data when rocket.test(data.id()) ->
                false;
            case ClientboundRemoveEntitiesPacket remove ->
                remove.entityIds().intStream().filter(id -> !rocket.test(id)).anyMatch(ours.or(near));
            case ClientboundAddEntityPacket spawn ->
                {
                    var at = new net.minecraft.world.phys.Vec3(spawn.getX(), spawn.getY(), spawn.getZ());
                    yield ours.test(spawn.getId()) || motions != null && motions.follows(Packets.type(spawn)) && around.inflate(4.0).intersects(at.x(), at.y(), at.z(), at.x(), at.y(), at.z());
                }
            case ClientboundSetPassengersPacket passengers ->
                ours.or(near).test(passengers.getVehicle()) || java.util.Arrays.stream(passengers.getPassengers()).anyMatch(ours.or(near));
            case ClientboundTeleportEntityPacket teleport ->
                ours.or(near).test(teleport.id());
            case ClientboundEntityPositionSyncPacket sync ->
                ours.or(near).test(sync.id());
            case ClientboundSetEntityDataPacket data ->
                vehicle.or(shaped).test(data.id());
            case ClientboundUpdateAttributesPacket attributes ->
                vehicle.or(shaped).test(attributes.getEntityId());
            case ClientboundUpdateMobEffectPacket effect ->
                vehicle.test(effect.getEntityId());
            case ClientboundRemoveMobEffectPacket effect ->
                vehicle.test(effect.entityId());
            case ClientboundSetEquipmentPacket equipment ->
                vehicle.or(ghast).test(equipment.getEntity());
            // the movement a mount starts from when the player drives it
            case ClientboundSetEntityMotionPacket velocity ->
                vehicle.or(near).test(velocity.id());
            case ClientboundSetTimePacket ignored ->
                riding;
            // the rain decides a riptide release, which looks at the rain changes in flight itself (target.raining)
            case ClientboundGameEventPacket ignored ->
                false;
            // light and biomes decide only the rain at a position, which looks at them in flight itself (target.inRain)
            case ClientboundLightUpdatePacket ignored ->
                false;
            case ClientboundChunksBiomesPacket ignored ->
                false;
            // profiles name player entities created later; another player's game mode decides whether it pushes
            case ClientboundPlayerInfoUpdatePacket info ->
                info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE) && !info.entries().isEmpty();
            case ClientboundPlayerInfoRemovePacket ignored ->
                false;
            // a block change that leaves the movement as it is (a bed's occupancy as someone lies down or gets up)
            case ClientboundBlockUpdatePacket update ->
                level == null || !movementIrrelevant(level, update.getPos().getX(), update.getPos().getY(), update.getPos().getZ(), net.minecraft.world.level.block.Block.getId(update.getBlockState()));
            case MultiBlockChangeBitRepackHandler.Update update ->
                {
                    boolean relevant = level == null;
                    for (int i = 0; !relevant && i < update.count(); i++) {
                        relevant = !movementIrrelevant(level, update.blockX(i), update.blockY(i), update.blockZ(i), update.stateId(i));
                    }
                    yield relevant;
                }
            // a bed's block entity holds its colour only
            case ClientboundBlockEntityDataPacket data ->
                !net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(data.getType()).toString().equals("minecraft:bed");
            default ->
                !Packets.entityMove(change) || ours.or(near).test(Packets.entityId(change));
        };
    }

    /**
     * Whether a firework rocket's {@code add_entity} with this id is among the world changes not applied yet.
     */
    private boolean pendingRocket(int id) {
        for (var change : world.pendingBefore(Long.MAX_VALUE)) {
            if (change instanceof ClientboundAddEntityPacket spawn && spawn.getId() == id && Packets.type(spawn).equals(EntityMotions.ROCKET))
                return true;
        }
        return false;
    }

    /**
     * Whether a player change still in flight concerns the player: an entity teleport only for its own or former vehicle id.
     */
    private boolean concernsPlayerModel(Object change) {
        if (!(change instanceof ClientboundTeleportEntityPacket teleport))
            return true;
        CompensatedWorld level = world.world();
        var entities = level == null ? null : level.entities();
        return entities == null || teleport.id() == entities.localPlayerId() || entities.formerVehicle(teleport.id()) != Tri.FALSE;
    }

    /**
     * How far around the player an in-flight entity change counts (blocks, beyond the entity's own reach).
     */
    private static final double NEAR = 8.0;

    /**
     * A change that sets the player's velocity outright: an absolute-velocity teleport, a motion packet, a new player.
     */
    private static boolean setsVelocity(Object change, PlayerState player) {
        return switch(change) {
            case ClientboundPlayerPositionPacket teleport ->
                {
                    int relatives = net.minecraft.world.entity.Relative.pack(teleport.relatives());
                    yield !player.passenger && !ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.DELTA_X) && !ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.DELTA_Y) && !ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.DELTA_Z);
                }
            case ClientboundSetEntityMotionPacket ignored ->
                true;
            case ClientboundLoginPacket ignored ->
                true;
            case ClientboundRespawnPacket ignored ->
                true;
            default ->
                false;
        };
    }

    private static double distance(Vec3d a, Vec3d b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Blocks and entities of the world hypothesis for the player's interactions ({@code null}: not known).
     */
    private ClientPlayerActions.Target target(CompensatedWorld level) {
        return target(level, null);
    }

    /**
     * {@link #target(CompensatedWorld)} for the actions of one state ({@code chain}; {@code null}: it moves nobody).
     */
    private ClientPlayerActions.Target target(CompensatedWorld level, Chain chain) {
        if (level == null)
            return ClientPlayerActions.Target.UNKNOWN;
        return new ClientPlayerActions.Target() {

            @Override
            public Tri raining() {
                // a rain change still in flight may have reached the client
                for (var change : world.pendingBefore(Long.MAX_VALUE)) {
                    if (change instanceof ClientboundGameEventPacket event && CompensatedWorld.rainEvent(event))
                        return Tri.EITHER;
                }
                return level.raining();
            }

            @Override
            public Tri inRain(PlayerState player) {
                Tri raining = raining();
                if (raining == Tri.FALSE)
                    return Tri.FALSE;
                Tri at = ru.minecomplex.mac.world.Precipitation.inRain(level, world.pendingBefore(Long.MAX_VALUE), tags(level), biomes, player.position.x(), player.position.y(), player.position.z(), player.boundingBox().maxY());
                return at == Tri.FALSE ? Tri.FALSE : at == Tri.TRUE ? raining : Tri.EITHER;
            }

            @Override
            public void moveSelf(PlayerState player, Vec3d delta, Set<Unmodeled> gaps) {
                if (chain == null || chain.player.player() != player) {
                    player.riptideUnknownCause = "неизвестен подъём рывка от земли";
                    gaps.add(Unmodeled.RIPTIDE);
                    return;
                }
                int me = level.entities() == null ? -1 : level.entities().localPlayerId();
                var physics = new MovementTickerPlayer(environment(level, () -> EntityMotions.Collider.player(me, player.position), gaps, null), player, chain.player.local(), chain.player.data());
                physics.moveSelf(delta);
                gaps.addAll(physics.unmodeled());
            }

            @Override
            public String block(int x, int y, int z) {
                int state = level.getBlock(x, y, z);
                return state == CompensatedWorld.UNLOADED ? null : registry.block(state).name();
            }

            @Override
            public boolean blockUseConsumes(ServerboundUseItemOnPacket use) {
                var at = use.hitResult().getBlockPos();
                return chain != null && ru.minecomplex.mac.world.OpenableBlocks.consumes(registry, level.getBlock(at.getX(), at.getY(), at.getZ()), chain.player.player(), chain.player.data(), use);
            }

            @Override
            public Double sleepHeight(int x, int y, int z) {
                return level.sleepHeight(x, y, z);
            }

            @Override
            public ru.minecomplex.mac.world.BedStandUp.Outcome stopSleeping(int x, int y, int z, float yRot, Set<Unmodeled> gaps) {
                return ru.minecomplex.mac.world.BedStandUp.stopSleeping(level, registry, tags(level), x, y, z, yRot, gaps);
            }

            @Override
            public String entityType(int entityId) {
                return level.entities() == null ? null : level.entities().typeOf(entityId);
            }

            @Override
            public Tri formerVehicle(int entityId) {
                return level.entities() == null ? Tri.EITHER : level.entities().formerVehicle(entityId);
            }
        };
    }

    /**
     * The local player as the entities of the level push it (between and around its own ticks): every state kept, each
     * pushed and moved on its own ({@link EntityMotions.LocalPlayerTarget#possible()}).
     */
    private EntityMotions.LocalPlayerTarget pushTarget(CompensatedWorld level) {
        var entities = level.entities();
        if (entities == null)
            return null;
        List<EntityMotions.LocalPlayerTarget> possible = new ArrayList<>();
        for (Chain chain : chains) possible.add(pushTarget(level, chain.player, possible));
        return possible.get(0);
    }

    /**
     * One state as a push target; {@code all} lists every state's target.
     */
    private EntityMotions.LocalPlayerTarget pushTarget(CompensatedWorld level, PlayerModel.Hypothesis player, List<EntityMotions.LocalPlayerTarget> all) {
        var entities = level.entities();
        int me = entities.localPlayerId();
        return new EntityMotions.LocalPlayerTarget() {

            @Override
            public Vec3d position() {
                return player.player().position;
            }

            @Override
            public Aabb box() {
                return player.player().boundingBox();
            }

            @Override
            public boolean pushable() {
                PlayerState p = player.player();
                return new MovementTickerPlayer(environment(level, () -> EntityMotions.Collider.player(me, p.position), EnumSet.noneOf(Unmodeled.class), null), p, player.local(), player.data()).isPushable();
            }

            @Override
            public boolean spectator() {
                return player.player().spectator;
            }

            @Override
            public boolean noPhysics() {
                return player.player().noPhysics;
            }

            @Override
            public boolean vehicle() {
                var own = entities.passengers().get(me);
                return own != null && !own.passengers().isEmpty();
            }

            @Override
            public boolean sleeping() {
                return player.player().sleeping;
            }

            @Override
            public Vec3d velocity() {
                return player.player().deltaMovement;
            }

            @Override
            public void velocity(Vec3d value) {
                player.player().deltaMovement = value;
            }

            @Override
            public void moveByShulker(Vec3d delta, Set<Unmodeled> gaps) {
                PlayerState p = player.player();
                var physics = new MovementTickerPlayer(environment(level, () -> EntityMotions.Collider.player(me, p.position), gaps, null), p, player.local(), player.data());
                physics.moveByShulkerEntity(delta);
                gaps.addAll(physics.unmodeled());
            }

            @Override
            public List<EntityMotions.LocalPlayerTarget> possible() {
                return all;
            }

            @Override
            public void rocketBoost(int id) {
                // a state that handled the rocket's removal (or its detachment) ahead of this model no longer has it
                if (rocketsAhead(player.appliedThrough()).detached(id))
                    return;
                PlayerState p = player.player();
                if (p.fallFlying)
                    p.deltaMovement = fireworks.boost(p.deltaMovement, p.yRot, p.xRot);
            }
        };
    }

    /**
     * The simulated entities' {@code level.noCollision(entity, box)}: blocks and the entities that entity collides with.
     * The local player's box is the lead's; where the states kept disagree on it, a query reaching any of their boxes is
     * not resolved.
     */
    private EntityMotions.BodyQueries bodyQueries(CompensatedWorld level) {
        Aabb lead = lead().player().boundingBox();
        List<Aabb> boxes = new ArrayList<>();
        for (Chain chain : chains) {
            Aabb box = chain.player.player().boundingBox();
            if (!box.equals(lead))
                boxes.add(box);
        }
        if (!boxes.isEmpty())
            boxes.add(lead);
        // the local player is an obstacle for a vehicle when it is pushable: known when every state kept agrees
        Boolean pushable = null;
        boolean agree = true;
        int me = level.entities() == null ? -1 : level.entities().localPlayerId();
        for (Chain chain : chains) {
            PlayerState p = chain.player.player();
            boolean one = new MovementTickerPlayer(environment(level, () -> EntityMotions.Collider.player(me, p.position), EnumSet.noneOf(Unmodeled.class), null), p, chain.player.local(), chain.player.data()).isPushable();
            if (pushable == null)
                pushable = one;
            else if (pushable != one)
                agree = false;
        }
        Tri leadPushable = !agree || pushable == null ? Tri.EITHER : pushable ? Tri.TRUE : Tri.FALSE;
        return (id, body, self, queryGaps) -> box -> {
            for (Aabb player : boxes) {
                if (box.intersects(player.minX(), player.minY(), player.minZ(), player.maxX(), player.maxY(), player.maxZ())) {
                    queryGaps.add(Unmodeled.ENTITY_COLLISION);
                }
            }
            java.util.function.Supplier<EntityMotions.Collider> now = () -> new EntityMotions.Collider(self.id(), self.player(), self.vehicleRule(), self.happyGhast(), body.position);
            return new ru.minecomplex.mac.mechanics.collision.EntityBodyQueries(environment(level, now, queryGaps, lead, leadPushable), body).free(box);
        };
    }

    /**
     * The world hypothesis as the movement engine reads it, for a body moving itself ({@code source}; {@code null}:
     * not resolvable, every entity query is a gap).
     *
     * @param queryGaps      collects what the entity queries could not resolve
     * @param localPlayerBox the local player's box when another entity moves (a pushable entity for vehicles)
     */
    /**
     * Whether a fluid may push a remote entity with this box ({@code Entity.updateFluidHeightAndDoFluidPushing} in its base
     * tick adds the normalized mean flow of the fluid blocks it touches): a touched column not loaded, or a touched fluid
     * block with a flow ({@code FlowingFluid.getFlow}); still fluid does not push. The touched range is widened by 0.001.
     */
    private java.util.function.Function<Vec3d, Tri> climbable(CompensatedWorld level) {
        // LivingEntity.onClimbable of a mob: its block in #climbable, or an open trapdoor over a ladder (not resolved here)
        return at -> {
            int state = level.getBlock((int) Math.floor(at.x()), (int) Math.floor(at.y()), (int) Math.floor(at.z()));
            if (state == CompensatedWorld.UNLOADED)
                return Tri.EITHER;
            var type = registry.block(state);
            if (tags(level).contains(ru.minecomplex.mac.world.BlockTag.CLIMBABLE, type))
                return Tri.TRUE;
            return type.name().endsWith("_trapdoor") ? Tri.EITHER : Tri.FALSE;
        };
    }

    private java.util.function.Predicate<Aabb> fluidPush(CompensatedWorld level) {
        boolean[] uncertain = { false };
        var fluids = new ru.minecomplex.mac.mechanics.fluid.FluidWorld(environment(level, () -> null, EnumSet.noneOf(Unmodeled.class), null), gap -> uncertain[0] = true);
        return box -> {
            if (!level.fluidIn(box))
                return false;
            int minX = (int) Math.floor(box.minX()), maxX = (int) Math.floor(box.maxX());
            int minY = (int) Math.floor(box.minY()), maxY = (int) Math.floor(box.maxY());
            int minZ = (int) Math.floor(box.minZ()), maxZ = (int) Math.floor(box.maxZ());
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                if (!level.isLoaded(x >> 4, z >> 4))
                    return true;
                for (int y = minY; y <= maxY; y++) {
                    if (fluids.kind(x, y, z) == 0)
                        continue;
                    uncertain[0] = false;
                    Vec3d flow = fluids.flow(x, y, z);
                    if (uncertain[0] || flow.lengthSqr() != 0.0)
                        return true;
                }
            }
            return false;
        };
    }

    private MovementEnvironment environment(CompensatedWorld level, java.util.function.Supplier<EntityMotions.Collider> source, Set<Unmodeled> queryGaps, Aabb localPlayerBox) {
        return environment(level, source, queryGaps, localPlayerBox, Tri.EITHER);
    }

    /**
     * {@link #environment(CompensatedWorld, java.util.function.Supplier, Set, Aabb)} knowing whether that player is pushable.
     */
    private MovementEnvironment environment(CompensatedWorld level, java.util.function.Supplier<EntityMotions.Collider> source, Set<Unmodeled> queryGaps, Aabb localPlayerBox, Tri localPlayerPushable) {
        BlockTags blockTags = tags(level);
        var motions = level.entities() == null ? null : level.entities().motions();
        boolean nether = level.dimension().equals("minecraft:the_nether");
        return new MovementEnvironment() {

            @Override
            public boolean fluidsResolved() {
                return true;
            }

            @Override
            public Tri fastLava() {
                return nether ? Tri.TRUE : Tri.FALSE;
            }

            @Override
            public BlockSource blocks() {
                return level;
            }

            @Override
            public BlockRegistry registry() {
                return registry;
            }

            @Override
            public BlockTags tags() {
                return blockTags;
            }

            @Override
            public boolean hasChunkAt(int blockX, int blockZ) {
                return level.isLoaded(blockX >> 4, blockZ >> 4);
            }

            @Override
            public int minY() {
                return level.minY();
            }

            @Override
            public List<VoxelShape> entityCollisions(Aabb box) {
                var collider = source.get();
                if (motions == null || collider == null) {
                    queryGaps.add(Unmodeled.ENTITY_COLLISION);
                    return List.of();
                }
                return motions.collisions(box, collider, localPlayerBox, localPlayerPushable, queryGaps);
            }

            @Override
            public Tri livingIn(Aabb area) {
                var collider = source.get();
                if (motions == null || collider == null)
                    return Tri.EITHER;
                return motions.livingIn(area, collider.id());
            }

            @Override
            public VoxelShape borderShape(Aabb box) {
                // isInsideCloseToBorder(entity, box) reads the moving entity's position now; without a source the entity
                // query of the same collision already reported the gap
                var collider = source.get();
                if (collider == null)
                    return null;
                if (collider.position() == null) {
                    // a vehicle the ride does not simulate
                    queryGaps.add(Unmodeled.RIDING);
                    return null;
                }
                return level.borderShape(collider.position().x(), collider.position().z(), box, queryGaps);
            }
        };
    }
}
