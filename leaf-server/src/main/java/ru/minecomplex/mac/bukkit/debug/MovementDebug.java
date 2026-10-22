package ru.minecomplex.mac.bukkit.debug;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.minecomplex.mac.bukkit.net.SessionHandle;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.engine.MovementCheckRunner;

/**
 * {@code /mac debug}: shows viewers how closely the core predicts a player's movement ({@link MovementCheckRunner}): a chat
 * line for every position the client sends (predicted minus reported per axis, the changes still in flight, the
 * unresolved facts) and a summary in the action bar. Reports arrive on the player's event loop and are aggregated
 * there; messages are sent from the main thread, in order.
 */
public final class MovementDebug {
    private static final int ACTION_BAR_EVERY = 5;

    private final java.util.function.Consumer<Runnable> mainThread;
    private final Map<UUID, Watch> watches = new ConcurrentHashMap<>();

    public MovementDebug(java.util.function.Consumer<Runnable> mainThread) {
        this.mainThread = mainThread;
    }

    /** Toggles {@code viewer}'s subscription to {@code target}'s reports; returns whether it is now on. */
    public boolean toggle(Player viewer, Player target, SessionHandle handle) {
        Watch watch = watches.computeIfAbsent(target.getUniqueId(), id -> new Watch(target.getName(), handle));
        if (!watch.viewers.remove(viewer.getUniqueId())) {
            watch.viewers.add(viewer.getUniqueId());
            handle.session().probeListener(watch::report);
            return true;
        }
        if (watch.viewers.isEmpty()) {
            handle.session().probeListener(null);
            watches.remove(target.getUniqueId());
        }
        return false;
    }

    /** A summary line of {@code target}'s current watch, or {@code null} when nobody watches it. */
    public String summary(UUID target) {
        Watch watch = watches.get(target);
        return watch == null ? null : watch.summary();
    }

    public void stopAll() {
        for (Watch watch : watches.values()) watch.handle.session().probeListener(null);
        watches.clear();
    }

    private final class Watch {
        final String name;
        final SessionHandle handle;
        final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
        // confined to the target's event loop
        long compared, exact, close, near, off, skipped, windowOpen;
        double secondMax;
        int sinceBar;
        long secondStart = System.nanoTime();
        String lastSkip = "";

        Watch(String name, SessionHandle handle) {
            this.name = name;
            this.handle = handle;
        }

        void report(MovementCheckRunner.TickReport report) {
            long now = System.nanoTime();
            if (now - secondStart > 1_000_000_000L) {
                secondStart = now;
                secondMax = 0;
            }
            if (report.status() == MovementCheckRunner.Status.SKIPPED) {
                skipped++;
                if (!report.note().equals(lastSkip)) {
                    broadcast(false, Component.text("[MAC] " + name + ": пропуск — " + report.note(), NamedTextColor.GRAY));
                }
                lastSkip = report.note();
            } else {
                lastSkip = "";
                compared++;
                if (report.windowOpen()) windowOpen++;
                double offset = report.offset();
                if (offset <= 1.0E-8) exact++;
                else if (offset <= 1.0E-4) close++;
                else if (offset <= 0.03) near++;
                else off++;
                secondMax = Math.max(secondMax, offset);
                // every movement the client sent, and any tick where the prediction moved although the client did not
                if (report.reported() != null || offset > 0.0) {
                    broadcast(false, chatLine(report));
                }
            }
            if (++sinceBar >= ACTION_BAR_EVERY) {
                sinceBar = 0;
                broadcast(true, barLine(report));
            }
        }

        Component barLine(MovementCheckRunner.TickReport report) {
            if (report.status() == MovementCheckRunner.Status.SKIPPED) {
                return Component.text("MAC " + name + ": пропуск — " + report.note(), NamedTextColor.GRAY);
            }
            NamedTextColor color = secondMax <= 1.0E-4 ? NamedTextColor.GREEN : secondMax <= 0.03 ? NamedTextColor.YELLOW : NamedTextColor.RED;
            String subject = report.vehicle() == null ? "" : " [" + report.vehicle() + "]";
            return Component.text(String.format(Locale.ROOT, "MAC %s%s: откл. %s | макс/с %s | точно %.1f%% | тиков %d",
                    name, subject, format(report.offset()), format(secondMax), percent(exact + close), compared), color);
        }

        Component chatLine(MovementCheckRunner.TickReport report) {
            Vec3d p = report.predicted(), r = report.reported();
            double offset = report.offset();
            NamedTextColor color = offset <= 1.0E-6 ? NamedTextColor.GREEN : offset <= 0.03 ? NamedTextColor.YELLOW : NamedTextColor.RED;
            String where = r == null
                    ? String.format(Locale.ROOT, "без позиции, пред %.6f %.6f %.6f", p.x(), p.y(), p.z())
                    : String.format(Locale.ROOT, "клиент %.6f %.6f %.6f | пред−клиент %s %s %s", r.x(), r.y(), r.z(),
                            signed(p.x() - r.x()), signed(p.y() - r.y()), signed(p.z() - r.z()));
            String extra = (report.inFlight().isEmpty() ? "" : " | в пути: " + counted(report.inFlight()))
                    + (report.gaps().isEmpty() ? "" : " | пробелы: " + report.gaps())
                    + (report.note().isEmpty() ? "" : " | " + report.note());
            String subject = report.vehicle() == null ? "" : report.vehicle() + " ";
            return Component.text(String.format(Locale.ROOT, "#%d %sΔ%s | %s%s", report.frame(), subject, format(offset), where, extra), color);
        }

        String summary() {
            return String.format(Locale.ROOT,
                    "%s: сравнено %d | бит-в-бит %.1f%% | ≤1e-4 %.1f%% | ≤0.03 %.1f%% | больше %.1f%% | открытое окно %d | пропущено %d%s",
                    name, compared, percent(exact), percent(close), percent(near), percent(off), windowOpen, skipped,
                    lastSkip.isEmpty() ? "" : " (последний пропуск: " + lastSkip + ")");
        }

        double percent(long count) {
            return compared == 0 ? 0.0 : 100.0 * count / compared;
        }

        void broadcast(boolean actionBar, Component message) {
            mainThread.accept(() -> {
                for (UUID id : viewers) {
                    Player viewer = Bukkit.getPlayer(id);
                    if (viewer == null) continue;
                    if (actionBar) viewer.sendActionBar(message);
                    else viewer.sendMessage(message);
                }
            });
        }
    }

    static String signed(double delta) {
        return delta == 0.0 ? "0" : String.format(Locale.ROOT, "%+.2e", delta);
    }

    /** "ChunkData×3, EntityVelocity" */
    static String counted(java.util.List<String> names) {
        var counts = new java.util.LinkedHashMap<String, Integer>();
        for (String name : names) counts.merge(name, 1, Integer::sum);
        var parts = new java.util.ArrayList<String>();
        counts.forEach((name, count) -> parts.add(count == 1 ? name : name + "×" + count));
        return String.join(", ", parts);
    }

    static String format(double offset) {
        return offset == 0.0 ? "0" : String.format(Locale.ROOT, "%.2e", offset);
    }
}
