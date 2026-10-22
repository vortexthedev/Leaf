package ru.minecomplex.mac.fixture.inbound;

/** Native swing/punch fixture; item drops do not imply a swing. */
public record Swing(Hand hand) implements InboundEvent {
    public enum Hand {
        MAIN_HAND
    }
}
