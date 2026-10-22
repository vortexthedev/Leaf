package ru.minecomplex.mac.fixture.outbound;

/** {@code bundle_delimiter}; toggles the client's bundle packer. */
public record BundleDelimiter() implements OutboundEvent {
    public static final BundleDelimiter INSTANCE = new BundleDelimiter();
}
