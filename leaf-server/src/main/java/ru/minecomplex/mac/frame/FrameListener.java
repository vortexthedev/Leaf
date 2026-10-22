package ru.minecomplex.mac.frame;

/**
 * Receives assembled frames and between-tick events, in wire order.
 */
public interface FrameListener {

    void betweenTicks(BetweenTicks event);

    void frame(ClientTickFrame frame);
}
