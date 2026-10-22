package ru.minecomplex.mac.player;

import org.junit.jupiter.api.Test;

import net.minecraft.world.level.GameType;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.*;

import ru.minecomplex.mac.fixture.outbound.*;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.listener.ClientPlayerHandlers;
import ru.minecomplex.mac.player.state.ClientPlayerData;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerState;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class PlayerInfoIdentityTest {

    @Test
    void forksKeepDetachedAndAttachedObjectsIndependent() {
        PlayerModel model = new PlayerModel();
        var branch = model.start(new PlayerState(), new LocalPlayerState(), new ClientPlayerData(), -1);
        model.sent(1, Wire.of(new PlayerInfoGameMode(true, true, 3)));
        branch.apply(new SyncWindow(-1, 2), 1);
        branch.livingTickStart(); // a real player tick reads isSpectator and caches the entry
        var fork = branch.fork();
        model.sent(2, Wire.of(new PlayerInfoRemove()));
        model.sent(3, Wire.of(new PlayerInfoGameMode(true, true, 1)));
        branch.apply(new SyncWindow(-1, 4), 3);
        fork.apply(new SyncWindow(-1, 4), 1);
        assertTrue(branch.player().spectator);
        assertEquals(GameType.CREATIVE, branch.data().infoMode);
        assertEquals(GameType.SPECTATOR, branch.data().playerInfoMode());
        assertTrue(branch.gaps().isEmpty());
        assertTrue(fork.data().infoPresent);
        assertTrue(fork.data().infoCacheAttached);
        assertEquals(GameType.SPECTATOR, fork.data().infoMode);
        var detached = branch.fork();
        model.sent(4, Wire.of(new PlayerInfoGameMode(false, true, 0)));
        branch.apply(new SyncWindow(-1, 5), 4);
        assertEquals(GameType.CREATIVE, detached.data().infoMode);
        assertEquals(GameType.SPECTATOR, branch.data().playerInfoMode());
    }

    @Test
    void onlyAnUnobservedFirstLookupRemainsUncertainUntilRecreation() {
        var h = new ClientPlayerHandlers();
        var p = new PlayerState(); var l = new LocalPlayerState(); var d = new ClientPlayerData();
        d.localMode = GameType.SURVIVAL;
        assertFalse(h.apply(p, l, d, Wire.of(new PlayerInfoRemove())).gaps().contains(Unmodeled.PLAYER_INFO_IDENTITY));
        h.apply(p, l, d, Wire.of(new PlayerInfoGameMode(true, true, 3)));
        // The renderer could read getSkin between these packets, but need not have rendered a frame.
        assertTrue(h.apply(p, l, d, Wire.of(new PlayerInfoRemove())).gaps().contains(Unmodeled.PLAYER_INFO_IDENTITY));
        assertTrue(h.apply(p, l, d, Wire.of(new PlayerInfoGameMode(true, true, 0))).gaps().contains(Unmodeled.PLAYER_INFO_IDENTITY));
        var recreated = h.apply(p, l, d, Wire.of(new Respawn("minecraft:overworld", 3, -64, 384)));
        assertTrue(recreated.resolves().contains(Unmodeled.PLAYER_INFO_IDENTITY));
        assertFalse(d.infoCached);
        assertEquals(GameType.SURVIVAL, d.playerInfoMode());
        assertFalse(h.apply(recreated.player(), recreated.local(), d, Wire.of(new PlayerInfoRemove()))
                .gaps().contains(Unmodeled.PLAYER_INFO_IDENTITY));
    }

    @Test
    void loginDropsBothMapAndCachedIdentity() {
        var h = new ClientPlayerHandlers();
        var p = new PlayerState(); var l = new LocalPlayerState(); var d = new ClientPlayerData();
        h.apply(p, l, d, Wire.of(new PlayerInfoGameMode(true, true, 3)));
        d.playerInfoMode();
        h.apply(p, l, d, Wire.of(new PlayerInfoRemove()));
        var fresh = h.apply(p, l, d, Wire.of(new JoinGame(7, "minecraft:overworld", -64, 384, 10)));
        assertNull(d.playerInfoMode());
        assertFalse(fresh.player().spectator);
        assertFalse(d.infoCached);
    }
}
