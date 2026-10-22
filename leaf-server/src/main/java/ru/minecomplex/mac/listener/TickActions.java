package ru.minecomplex.mac.listener;

/** The keybind/player-tick ordering facts of a native 26.3 client tick. */
public final class TickActions {
    boolean dropSeen;
    boolean playerTicked;
    public TickActions copy() {
        var copy=new TickActions();copy.dropSeen=dropSeen;copy.playerTicked=playerTicked;return copy;
    }
}
