package ru.minecomplex.mac.player;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import ru.minecomplex.mac.player.connection.PlayerConnectionContext;
import ru.minecomplex.mac.packet.NativeRuntime;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import ru.minecomplex.mac.packet.PacketDecoder;
import ru.minecomplex.mac.replay.Recording;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import ru.minecomplex.mac.frame.BetweenTicks;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.frame.FrameAssembler;
import ru.minecomplex.mac.frame.FrameListener;
import ru.minecomplex.mac.engine.MovementCheckRunner;
import ru.minecomplex.mac.player.transaction.MarkerIds;
import ru.minecomplex.mac.player.transaction.MarkerPolicy;
import ru.minecomplex.mac.player.transaction.PongResult;
import ru.minecomplex.mac.player.transaction.SyncTimeline;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.player.transaction.PlayerTimeline;
import ru.minecomplex.mac.util.ThreadConfinement;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.replica.WorldModel;
import ru.minecomplex.mac.replay.SessionRecorder;

/**
 * Per-player entry point of the core. Receives the native Leaf packet inputs of a play connection in wire order
 * ({@link #receive}, {@link #send}) and composes the specialised components; holds no physics state itself.
 *
 * <p>Confined to the player's event loop (asserted with {@code -ea}).
 */
public final class MacPlayer {
    /** Markers written but not yet observed; bounded because another plugin may cancel our pings. */
    private static final int MAX_UNOBSERVED_MARKERS = 64;

    private final PlayerConnectionContext facts;
    private final PlayerTimeline timeline = new PlayerTimeline();
    private final SyncTimeline sync = new SyncTimeline();
    private final MarkerIds markerIds = new MarkerIds();
    private final ArrayDeque<Integer> unobservedMarkers = new ArrayDeque<>();
    private final FrameAssembler frames;
    private final WorldModel world;
    private final PlayerModel player;
    private final ThreadConfinement confinement = new ThreadConfinement();
    private SessionRecorder recorder;
    private boolean bundleOpen;
    /** Block marker requests share Grim's cooldown; other critical packets must never be throttled. */
    private long lastBlockMarkerNanos;
    private boolean blockMarkerClockStarted;
    /** Dimension of the last {@code login}/{@code respawn} on the wire; a respawn into it keeps the level. */
    private String sentDimension;
    /** The client's own entity id from {@code login} (native id space). */
    private int playerEntityId = Integer.MIN_VALUE;
    /** Entities the player collides with, as sent ({@link MarkerPolicy#collidable}): their moves get a marker. */
    private final java.util.Set<Integer> collidableIds = new java.util.HashSet<>();
    /** The firework rockets sent ({@link MarkerPolicy#rocket}): their attachment and removal get a marker of their own. */
    private final java.util.Set<Integer> rocketIds = new java.util.HashSet<>();
    /** Diagnostic movement replay; {@code null} after it failed. */
    private MovementCheckRunner probe;
    private volatile Consumer<MovementCheckRunner.TickReport> probeListener;
    private java.util.function.BiConsumer<ClientTickFrame, MovementCheckRunner.TickReport> diagnosticListener;
    private volatile RuntimeException probeFailure;

    public MacPlayer(FrameListener frameListener) {
        this(frameListener, new PlayerConnectionContext(null, null));
    }

    /** @param facts the connection's profile ({@code PacketDecoder} wrappers carry no account data) */
    public MacPlayer(FrameListener frameListener, PlayerConnectionContext facts) {
        NativeRuntime.ensure();
        this.facts = facts;
        this.world = new WorldModel(BlockRegistry.get(), facts);
        this.player = new PlayerModel(facts);
        this.frames = new FrameAssembler(new FrameListener() {
            @Override
            public void betweenTicks(BetweenTicks event) {
                // handleMovePlayer accepts the teleport on the main thread, in wire order: it and everything before it
                // were handled, as a pong would tell
                if (event instanceof BetweenTicks.TeleportAccepted accepted) sync.handledThrough(accepted.teleportSeq());
                frameListener.betweenTicks(event);
            }

            @Override
            public void frame(ClientTickFrame frame) {
                // Everything certain for this tick is part of the acknowledged world from now on
                world.settle(frame.window());
                player.settle(frame.window());
                probe(frame);
                frameListener.frame(frame);
            }
        });
        this.probe = new MovementCheckRunner(world, player, currentWindow());
    }

    private void probe(ClientTickFrame frame) {
        MovementCheckRunner.TickReport report = null;
        try {
            if (probe != null) report = probe.frame(frame);
            Consumer<MovementCheckRunner.TickReport> listener = probeListener;
            if (listener != null && report != null) {
                listener.accept(report);
            }
        } catch (RuntimeException failure) {
            // diagnostics must never break the session
            probe = null;
            probeFailure = failure;
        }
        if (diagnosticListener != null) diagnosticListener.accept(frame, report);
    }

    /** Independent of the live debug subscriber; a null report exposes predictor failure to diagnostics. */
    public void diagnosticListener(java.util.function.BiConsumer<ClientTickFrame, MovementCheckRunner.TickReport> listener) {
        diagnosticListener = listener;
    }

    /** The diagnostic movement replay of this client, or {@code null} after it failed ({@link #probeFailure()}). */
    public MovementCheckRunner probe() {
        return probe;
    }

    public RuntimeException probeFailure() {
        return probeFailure;
    }

    /** Receives every tick report on the session's thread; {@code null} to stop. */
    public void probeListener(Consumer<MovementCheckRunner.TickReport> listener) {
        this.probeListener = listener;
    }

    /** Result of an inbound event for the transport layer. */
    public enum InboundDecision {
        /** Let the packet continue to Leaf. */
        PASS,
        /** Our own pong: drop it from Leaf and Paper see it. */
        CANCEL
    }

    /**
     * A serverbound play packet as native 26.3 delivered it from Leaf (the event's reader index at the payload): it is
     * recorded as received and decoded ({@link PacketDecoder}) into the wrapper {@link #inbound} processes.
     */
    public InboundDecision receive(ru.minecomplex.mac.packet.PacketInput event, long nanos) {
        assert confinement.check();
        if (recorder != null) recorder.packet(event.wire());
        return inbound(event.value(), nanos);
    }

    /**
     * A clientbound play packet as it goes on the wire from Leaf (the event's reader index at the payload): recorded,
     * decoded and processed ({@link #outbound}).
     *
     * @return whether the transport should write a marker right after it ({@link MarkerPolicy})
     */
    public boolean send(ru.minecomplex.mac.packet.PacketInput event, long nanos) {
        assert confinement.check();
        if (recorder != null) recorder.packet(event.wire());
        if (event.value() instanceof ClientboundLevelChunkWithLightPacket chunk) world.rememberChunkRegistries(chunk, event.registries());
        return outbound(event.value(), nanos);
    }

    /**
     * A packet of the configuration phase before the login, taken when it was sent ({@code packet}, its bytes) and handed
     * over when the session starts: recorded; the {@code update_tags} the session reads ({@code tags}, decoded when it was
     * sent) is processed as a clientbound packet. Registry data only builds native registries of the connection.
     */
    public void configuration(Recording.Packet packet, ClientboundUpdateTagsPacket tags) {
        assert confinement.check();
        if (recorder != null) recorder.packet(packet);
        if (tags != null) outbound(tags, packet.nanos());
    }

    /** A serverbound packet in decoded form (wire order); {@link #receive} is the transport's entry. */
    public InboundDecision inbound(Object event, long nanos) {
        assert confinement.check();
        long seq = timeline.nextInbound();
        if (event instanceof ServerboundPongPacket pong) {
            PongResult result = sync.pong(pong.getId(), nanos);
            frames.pong(seq, pong.getId(), result);
            return result.ours() ? InboundDecision.CANCEL : InboundDecision.PASS;
        }
        frames.accept(seq, event, currentWindow());
        return InboundDecision.PASS;
    }

    /**
     * A clientbound packet in decoded form was observed on the wire; {@link #send} is the transport's entry.
     *
     * @return whether the transport should write a marker right after it ({@link MarkerPolicy})
     */
    public boolean outbound(Object event, long nanos) {
        assert confinement.check();
        long seq = timeline.nextOutbound();
        boolean rocketChange = false;
        switch (event) {
            case ClientboundPingPacket ping -> {
                if (unobservedMarkers.remove(ping.getId())) {
                    sync.markerObserved(ping.getId(), seq, nanos);
                    // Tick/critical markers also bound the preceding block updates.
                    lastBlockMarkerNanos = nanos;
                    blockMarkerClockStarted = true;
                }
            }
            case ClientboundBundleDelimiterPacket ignored -> bundleOpen = !bundleOpen;
            case ClientboundLoginPacket join -> {
                sentDimension = join.commonPlayerSpawnInfo().dimension().identifier().toString();
                playerEntityId = join.playerId();
                collidableIds.clear();
                rocketIds.clear();
                timeline.startWorldEpoch(seq);
            }
            case ClientboundAddEntityPacket spawn -> {
                String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(spawn.getType()).toString();
                // A server-controlled mount moves its passenger even when it has no collision with the player.
                if (MarkerPolicy.collidable(type) || ru.minecomplex.mac.entity.EntityTypes.get().rideable(type) != null) collidableIds.add(spawn.getId());
                else collidableIds.remove(spawn.getId());
                if (MarkerPolicy.rocket(type)) rocketIds.add(spawn.getId());
                else rocketIds.remove(spawn.getId());
            }
            case ClientboundRemoveEntitiesPacket remove -> {
                for (int id : remove.entityIds()) {
                    collidableIds.remove(id);
                    rocketChange |= rocketIds.remove(id);
                }
            }
            case ClientboundSetEntityDataPacket data -> rocketChange = rocketIds.contains(data.id())
                    && !ru.minecomplex.mac.entity.EntityDataItems.of(data).isEmpty();
            case ClientboundRespawnPacket respawn -> {
                String dimension = respawn.commonPlayerSpawnInfo().dimension().identifier().toString();
                if (!dimension.equals(sentDimension)) {
                    collidableIds.clear();
                    rocketIds.clear();
                    sentDimension = dimension;
                    timeline.startWorldEpoch(seq);
                }
            }
            case ClientboundPlayerPositionPacket teleport -> {
                timeline.startTeleportEpoch();
                frames.teleportSent(seq, teleport);
            }
            case ClientboundPlayerRotationPacket rotation -> frames.rotationSent(seq, rotation);
            default -> {
            }
        }
        if (WorldModel.isWorldChange(event)) {
            world.sent(seq, event);
        }
        if (PlayerModel.isPlayerChange(event, playerEntityId, facts.localUuid())) {
            player.sent(seq, event);
        }
        if (MarkerPolicy.blockUpdate(event)) {
            // Keep every update above, suppress only redundant post-marker requests. The periodic tick marker
            // covers the final suppressed update even if the burst ends before this cooldown expires.
            if (blockMarkerClockStarted && nanos - lastBlockMarkerNanos < MarkerPolicy.BLOCK_UPDATE_INTERVAL_NANOS) return false;
            lastBlockMarkerNanos = nanos;
            blockMarkerClockStarted = true;
            return true;
        }
        return MarkerPolicy.markerAfter(event, playerEntityId, facts.localUuid()) || MarkerPolicy.motionMarker(event, collidableIds::contains)
                || rocketChange;
    }

    /**
     * Reserves an id for a marker the transport is about to write. The marker becomes part of the timeline only
     * when {@link #outbound} observes the ping on the wire.
     */
    public int allocateMarker(long nanos) {
        assert confinement.check();
        int id = markerIds.next();
        if (recorder != null) {
            recorder.markerAllocated(nanos, id);
        }
        if (unobservedMarkers.size() == MAX_UNOBSERVED_MARKERS) {
            unobservedMarkers.removeFirst();
        }
        unobservedMarkers.addLast(id);
        return id;
    }

    /** False while a bundle is open on the wire: a marker would be held back and could overflow the bundle. */
    public boolean canWriteMarker() {
        return !bundleOpen;
    }

    public SyncWindow currentWindow() {
        return sync.window(timeline.lastOutbound());
    }

    /** The connection's facts its packets are read with. */
    public PlayerConnectionContext facts() {
        return facts;
    }

    public PlayerTimeline timeline() {
        return timeline;
    }

    public SyncTimeline sync() {
        return sync;
    }

    public FrameAssembler frames() {
        return frames;
    }

    public WorldModel world() {
        return world;
    }

    /** Clientbound changes of the local player and the client-player hypotheses they apply to. */
    public PlayerModel player() {
        return player;
    }

    /** Starts ({@code recorder != null}) or stops recording. Call on the session's thread. */
    public void record(SessionRecorder recorder) {
        assert confinement.check();
        this.recorder = recorder;
    }

    /** The owning event loop changed (reconnection of the channel to another loop). */
    public void rebindThread() {
        confinement.rebind();
    }
}
