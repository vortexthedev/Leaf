package ru.minecomplex.mac.fixture.inbound;

/** {@code keep_alive} response. Sent from the client's netty thread: never a synchronization barrier. */
public record KeepAlive(long id) implements InboundEvent {
    @Override
    public boolean nettyThread() {
        return true;
    }
}
