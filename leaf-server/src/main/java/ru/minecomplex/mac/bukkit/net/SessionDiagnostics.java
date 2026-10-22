package ru.minecomplex.mac.bukkit.net;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import ru.minecomplex.mac.frame.BetweenTicks;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.frame.FrameListener;

/**
 * Receives frames on the player's event loop and keeps counters that other threads may read (commands). The
 * prediction engine will replace it as the frame consumer; the counters stay as telemetry.
 */
public final class SessionDiagnostics implements FrameListener {
    private final LongAdder frames = new LongAdder();
    private final LongAdder anomalousFrames = new LongAdder();
    private final LongAdder markersAcknowledged = new LongAdder();
    private final LongAdder skippedMarkers = new LongAdder();
    private final LongAdder teleportsAccepted = new LongAdder();
    private final LongAdder teleportAnomalies = new LongAdder();
    private final LongAdder outboundCancelled = new LongAdder();
    private final AtomicLong lastRoundTripNanos = new AtomicLong(-1);

    @Override
    public void betweenTicks(BetweenTicks event) {
        switch (event) {
            case BetweenTicks.MarkerAcknowledged ack -> {
                markersAcknowledged.increment();
                skippedMarkers.add(ack.skipped());
            }
            case BetweenTicks.TeleportAccepted accepted -> {
                teleportsAccepted.increment();
                if (accepted.skipped() > 0 || accepted.missingReply() || accepted.insideTick()) {
                    teleportAnomalies.increment();
                }
            }
            case BetweenTicks.UnknownTeleportAccept ignored -> teleportAnomalies.increment();
            default -> {
            }
        }
    }

    @Override
    public void frame(ClientTickFrame frame) {
        frames.increment();
        if (!frame.flags().isEmpty()) {
            anomalousFrames.increment();
        }
    }

    /** A clientbound packet another listener cancelled after we saw it; it never reached the session. */
    void outboundCancelled() {
        outboundCancelled.increment();
    }

    public long outboundCancelledCount() {
        return outboundCancelled.sum();
    }

    void roundTrip(long nanos) {
        lastRoundTripNanos.set(nanos);
    }

    public long frames() {
        return frames.sum();
    }

    public long anomalousFrames() {
        return anomalousFrames.sum();
    }

    public long markersAcknowledged() {
        return markersAcknowledged.sum();
    }

    public long skippedMarkers() {
        return skippedMarkers.sum();
    }

    public long teleportsAccepted() {
        return teleportsAccepted.sum();
    }

    public long teleportAnomalies() {
        return teleportAnomalies.sum();
    }

    public long lastRoundTripNanos() {
        return lastRoundTripNanos.get();
    }
}
