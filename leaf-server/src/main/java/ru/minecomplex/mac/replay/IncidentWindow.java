package ru.minecomplex.mac.replay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import ru.minecomplex.mac.engine.MovementCheckRunner;

/** Session-thread incident selection. Uncertain predictions are deliberately eligible too. */
public final class IncidentWindow {
    public record Incident(long trigger, long lastFrame, String reason, boolean completeTail, List<String> ticks) { }
    private final int before, after, cooldown, maxIncidents;
    private final double threshold;
    private final Consumer<Incident> sink;
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private List<String> pending;
    private long trigger, lastFrame = -1, nextTrigger;
    private String reason;
    private int saved;

    public IncidentWindow(int before, int after, int cooldown, int maxIncidents, double threshold, Consumer<Incident> sink) {
        if (before < 0 || after < 0 || cooldown < 0 || maxIncidents < 1 || !Double.isFinite(threshold) || threshold < 0)
            throw new IllegalArgumentException("Invalid incident limits");
        this.before = before;
        this.after = after;
        this.cooldown = cooldown;
        this.maxIncidents = maxIncidents;
        this.threshold = threshold;
        this.sink = sink;
    }

    public void tick(MovementCheckRunner.TickReport report, String details) {
        lastFrame = report.frame();
        String text = report + "\n" + details;
        if (text.length() > 65536) text = text.substring(0, 65536) + "\n[tick text truncated; see recording.macr]";
        boolean wasPending = pending != null;
        if (wasPending) pending.add(text);
        else if (saved < maxIncidents && lastFrame >= nextTrigger
                && report.status() == MovementCheckRunner.Status.COMPARED
                && (!Double.isFinite(report.offset()) || report.offset() > threshold)) {
            begin("offset=" + report.offset());
            pending.add(text);
        }
        recent.addLast(text);
        while (recent.size() > before) recent.removeFirst();
        if (pending != null && lastFrame - trigger >= after) finish(true);
    }

    /** Force a capture (also useful when the predictor failed or a symptom has no positional offset). */
    public void capture(String cause) {
        if (pending == null && saved < maxIncidents) begin(cause);
        else if (pending != null) reason += "; " + cause;
    }

    private void begin(String cause) {
        trigger = Math.max(0, lastFrame);
        reason = cause;
        pending = new ArrayList<>(recent);
    }

    private void finish(boolean complete) {
        var incident = new Incident(trigger, lastFrame, reason, complete, List.copyOf(pending));
        pending = null;
        saved++;
        nextTrigger = lastFrame + cooldown + 1;
        sink.accept(incident);
    }

    /** Disconnect/shutdown must not discard a pending incident just because fewer tail ticks arrived. */
    public void close() {
        if (pending != null) finish(false);
        recent.clear();
    }
}
