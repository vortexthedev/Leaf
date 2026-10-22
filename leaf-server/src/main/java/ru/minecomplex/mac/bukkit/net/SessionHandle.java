package ru.minecomplex.mac.bukkit.net;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import io.netty.channel.Channel;
import ru.minecomplex.mac.replay.Recording;
import ru.minecomplex.mac.replay.RecordingBuffer;
import ru.minecomplex.mac.player.MacPlayer;
import org.dreeam.leaf.network.PacketUser;
import net.minecraft.network.protocol.common.ClientboundPingPacket;

/**
 * Binds a {@link MacPlayer} to its connection. Mutations run on the channel event loop; the explicitly
 * asynchronous entry points queue their work there.
 */
public final class SessionHandle {
    private final PacketUser user;
    private final Channel channel;
    private final MacPlayer session;
    private final SessionDiagnostics diagnostics;
    private final ru.minecomplex.mac.player.connection.PlayerConnectionContext facts;
    private boolean markerDeferred;
    private boolean markerScheduled;
    private boolean closed;
    /** The session's inputs since its start, while it is recorded ({@code /mac record}). */
    private volatile RecordingBuffer recording;
    private volatile long recordingStartMillis;
    private AutomaticDiagnostics.Capture automatic;

    SessionHandle(PacketUser user, Channel channel, MacPlayer session, SessionDiagnostics diagnostics) {
        this.user = user;
        this.channel = channel;
        this.session = session;
        this.diagnostics = diagnostics;
        this.facts = new ru.minecomplex.mac.player.connection.PlayerConnectionContext(user.getName(), user.getUniqueId());
        // One observer multiplexes manual and automatic capture; stopping either must not stop the other.
        session.record(new ru.minecomplex.mac.replay.SessionRecorder() {
            @Override public void packet(ru.minecomplex.mac.replay.Recording.Packet packet) {
                if (recording != null) recording.packet(packet);
                if (automatic != null) automatic.journal.packet(packet);
            }
            @Override public void markerAllocated(long nanos, int id) {
                if (recording != null) recording.markerAllocated(nanos, id);
                if (automatic != null) automatic.journal.markerAllocated(nanos, id);
            }
        });
    }

    void automatic(AutomaticDiagnostics manager, ru.minecomplex.mac.world.Precipitation.Source biomes) {
        automatic = manager.start(this, biomes);
        session.diagnosticListener((frame, report) -> { if (automatic != null) automatic.tick(frame, report); });
    }

    void closeAutomatic() {
        closed = true;
        if (automatic != null) automatic.close();
    }

    /** From any thread; the capture is taken on the connection's event loop. */
    public void captureDiagnostic() {
        channel.eventLoop().execute(() -> { if (automatic != null) automatic.request(); });
    }

    /** Null while automatic diagnostics are available; safe for command telemetry. */
    public String diagnosticStatus() { return automatic == null ? "disabled or session limit reached" : automatic.status(); }

    /** From any thread: queue a marker behind everything already queued for this channel. */
    public void requestMarkerAsync() {
        if (channel.isActive()) {
            channel.eventLoop().execute(this::writeMarker);
        }
    }

    /**
     * On the event loop, outside of any pipeline handler: write a marker now, or once the open bundle closes
     * ({@code transactions.md} §1.4). Never call it from inside a packet listener: a synchronous write would land
     * before the packet being processed.
     */
    void writeMarker() {
        if (closed || !channel.isActive()) {
            return;
        }
        if (!session.canWriteMarker()) {
            markerDeferred = true;
            return;
        }
        markerDeferred = false;
        int id = session.allocateMarker(System.nanoTime());
        // Normal Leaf send: the observer must see the actual, possibly cancelled or rewritten marker.
        user.send(new ClientboundPingPacket(id));
    }

    /**
     * From the send listener: a marker right after the packet being written. Deferred to a task so it lands after
     * the packet (a synchronous write would overtake it); several requests before the task runs share one marker.
     */
    void requestMarkerAfterCurrent() {
        if (!markerScheduled) {
            markerScheduled = true;
            channel.eventLoop().execute(() -> {
                markerScheduled = false;
                writeMarker();
            });
        }
    }

    /** Called from the send listener after a bundle delimiter; defers so the marker lands after it. */
    void bundleToggled() {
        if (markerDeferred && session.canWriteMarker()) {
            channel.eventLoop().execute(this::writeMarker);
        }
    }

    /** On the event loop, before the session's first input: records the whole session. */
    void startRecording() {
        recording = new RecordingBuffer(0);
        recordingStartMillis = System.currentTimeMillis();
    }

    /** From any thread: whether the session is being recorded. */
    public boolean recording() {
        return recording != null;
    }

    /** Wall-clock start of the recording ({@link #recording()}). */
    long recordingStartMillis() {
        return recordingStartMillis;
    }

    /** On the event loop: ends the recording and returns it, {@code null} when the session was not recorded. */
    Recording takeRecording(String description) {
        RecordingBuffer buffer = recording;
        if (buffer == null) {
            return null;
        }
        recording = null;
        return buffer.snapshot(description);
    }

    public PacketUser user() {
        return user;
    }

    public Channel channel() {
        return channel;
    }

    public MacPlayer session() {
        return session;
    }

    /** The connection's facts the core reads its packets with. */
    public ru.minecomplex.mac.player.connection.PlayerConnectionContext facts() {
        return facts;
    }

    public SessionDiagnostics diagnostics() {
        return diagnostics;
    }
}
