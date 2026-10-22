package ru.minecomplex.mac.fixture;

import ru.minecomplex.mac.player.state.ClientPlayerData;

/** Client player data for tests that tick the local player directly. */
public final class FixturePlayers {
    private FixturePlayers() {
    }

    /** A loaded client ({@code hasClientLoaded}) with full food, no item in use and no glider equipped. */
    public static ClientPlayerData loaded() {
        ClientPlayerData data = new ClientPlayerData();
        data.clientLoaded = true;
        return data;
    }

    /** {@link #loaded()} with the given food level. */
    public static ClientPlayerData loaded(int food) {
        ClientPlayerData data = loaded();
        data.food = food;
        return data;
    }
}
