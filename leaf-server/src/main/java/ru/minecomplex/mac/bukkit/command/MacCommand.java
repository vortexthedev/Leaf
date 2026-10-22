package ru.minecomplex.mac.bukkit.command;

import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import ru.minecomplex.mac.bukkit.debug.MovementDebug;
import ru.minecomplex.mac.bukkit.net.SessionDiagnostics;
import ru.minecomplex.mac.bukkit.net.SessionHandle;
import ru.minecomplex.mac.bukkit.net.Sessions;

/**
 * {@code /mac sync <player>}: synchronization telemetry of a session (thread-safe counters only).
 * {@code /mac debug [player]}: toggles live movement prediction reports of a player (yourself by default);
 * {@code /mac debug stats [player]}: their summary.
 * {@code /mac record <player> [stop]}: records the player's next session from its start, or ends and saves the recording
 * (also saved when the player leaves), for replaying it through the same model ({@code Replayer}).
 */
public final class MacCommand implements TabExecutor {
    private final java.util.function.Consumer<Runnable> mainThread;
    private final Sessions sessions;
    private final MovementDebug debug;

    public MacCommand(java.util.function.Consumer<Runnable> mainThread, Sessions sessions, MovementDebug debug) {
        this.mainThread = mainThread;
        this.sessions = sessions;
        this.debug = debug;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("diagnose")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            SessionHandle handle = target == null ? null : sessions.byUuid(target.getUniqueId());
            if (handle == null) sender.sendMessage("MAC: нет сессии для " + args[1]);
            else {
                String status = handle.diagnosticStatus();
                if (status != null) sender.sendMessage("MAC: автодиагностика недоступна: " + status);
                else {
                    handle.captureDiagnostic();
                    sender.sendMessage("MAC: запрошен диагностический архив; после следующих тиков путь появится в консоли и mac/diagnostics");
                }
            }
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("debug")) {
            return debug(sender, label, args);
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("record")) {
            return record(sender, label, args);
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("sync")) {
            sender.sendMessage("/" + label + " sync <player> | /" + label + " debug [player] | /" + label + " debug stats [player] | /"
                    + label + " record <player> [stop] | /" + label + " diagnose <player>");
            return true;
        }
        Player player = Bukkit.getPlayerExact(args[1]);
        SessionHandle handle = player == null ? null : sessions.byUuid(player.getUniqueId());
        if (handle == null) {
            sender.sendMessage("No session for " + args[1]);
            return true;
        }
        SessionDiagnostics d = handle.diagnostics();
        sender.sendMessage(String.format(Locale.ROOT,
                "%s frames=%d anomalousFrames=%d markersAcked=%d skippedMarkers=%d teleports=%d teleportAnomalies=%d rtt=%.1fms",
                player.getName(), d.frames(), d.anomalousFrames(),
                d.markersAcknowledged(), d.skippedMarkers(), d.teleportsAccepted(), d.teleportAnomalies(),
                d.lastRoundTripNanos() / 1_000_000.0));
        return true;
    }

    private boolean record(CommandSender sender, String label, String[] args) {
        String name = args[1];
        Player online = Bukkit.getPlayerExact(name);
        SessionHandle handle = online == null ? null : sessions.byUuid(online.getUniqueId());
        if (args.length >= 3 && args[2].equalsIgnoreCase("stop")) {
            if (handle == null || !handle.recording()) {
                sender.sendMessage("MAC: сессия " + name + " не записывается");
                return true;
            }
            sender.sendMessage("MAC: сохраняю запись " + name + "…");
            sessions.save(handle, result -> mainThread.accept(() -> sender.sendMessage("MAC: запись " + name + ": " + result)));
            return true;
        }
        if (handle != null && handle.recording()) {
            sender.sendMessage("MAC: сессия " + name + " уже записывается; /" + label + " record " + name + " stop — сохранить");
            return true;
        }
        sessions.arm(name);
        sender.sendMessage("MAC: запись начнётся со следующего входа " + name + (online != null ? " (нужно перезайти)" : "")
                + "; сохранится при выходе или по /" + label + " record " + name + " stop в mac/recordings");
        return true;
    }

    private boolean debug(CommandSender sender, String label, String[] args) {
        boolean stats = args.length >= 2 && args[1].equalsIgnoreCase("stats");
        int nameIndex = stats ? 2 : 1;
        Player target = args.length > nameIndex ? Bukkit.getPlayerExact(args[nameIndex])
                : sender instanceof Player self ? self : null;
        if (target == null) {
            sender.sendMessage("/" + label + " debug [stats] <player>");
            return true;
        }
        SessionHandle handle = sessions.byUuid(target.getUniqueId());
        if (handle == null) {
            sender.sendMessage("Нет сессии для " + target.getName() + " (неподдерживаемый протокол?)");
            return true;
        }
        if (handle.session().probe() == null) {
            RuntimeException failure = handle.session().probeFailure();
            sender.sendMessage("Предсказатель " + target.getName() + " остановлен: " + (failure == null ? "?" : failure));
            return true;
        }
        if (stats) {
            String summary = debug.summary(target.getUniqueId());
            sender.sendMessage(summary == null ? "Никто не смотрит " + target.getName() + ": /" + label + " debug " + target.getName() : summary);
            return true;
        }
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage("Только для игроков; из консоли: /" + label + " debug stats <player>");
            return true;
        }
        boolean on = debug.toggle(viewer, target, handle);
        sender.sendMessage(on
                ? "MAC: предсказание движения " + target.getName()
                        + " — в чат каждое движение (Δ, пред−клиент по осям, что ещё в пути), в actionbar сводка"
                : "MAC: отладка " + target.getName() + " выключена");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return List.of("sync", "debug", "record", "diagnose");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("record")) {
            return List.of("stop");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            List<String> names = new java.util.ArrayList<>(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            names.add("stats");
            return names;
        }
        if (args.length == 2 || args.length == 3 && args[1].equalsIgnoreCase("stats")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }
}
