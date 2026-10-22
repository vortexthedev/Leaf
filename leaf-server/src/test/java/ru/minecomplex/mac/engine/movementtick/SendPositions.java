package ru.minecomplex.mac.engine.movementtick;

import java.util.List;
import java.util.Set;
import ru.minecomplex.mac.fixture.FixturePlayers;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.player.state.SendState;
import ru.minecomplex.mac.world.BlockRegistry;

/** {@code LocalPlayer.sendPosition} alone, for the direct send oracles. */
final class SendPositions {
    private SendPositions() {
    }

    /**
     * The packets {@link MovementTickerPlayer#sendPosition} sends for {@code player} with the given send state (updated in
     * place), input and options, and what it could not resolve.
     */
    static TickPackets send(PlayerState player, SendState sent, PlayerInput input,
                            boolean loaded, boolean controlledCamera, boolean autoJump, VehiclePacketState rootVehicle) {
        var local = new LocalPlayerState();
        local.input = input;
        local.send = sent;
        local.controlledCamera = controlledCamera;
        local.autoJumpOption = autoJump;
        local.rootVehicle = rootVehicle;
        var data = FixturePlayers.loaded();
        data.clientLoaded = loaded;
        var env = LocalPlayerFluidOracleTest.environment(BlockRegistry.get(), List.of());
        var ticker = new MovementTickerPlayer(env, player, local, data);
        ticker.sendPosition();
        return new TickPackets(List.copyOf(ticker.packets()), Set.copyOf(ticker.unmodeled()));
    }

    /** A ticker for the input functions alone ({@code keyboardVector}, {@code modifyInput}). */
    static MovementTickerPlayer inputPort() {
        var env = LocalPlayerFluidOracleTest.environment(BlockRegistry.get(), List.of());
        return new MovementTickerPlayer(env, new PlayerState(), new LocalPlayerState(), FixturePlayers.loaded());
    }
}
