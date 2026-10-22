package ru.minecomplex.mac.fixture.inbound;

/** Ordered boat paddle input; left and right are the wire fields, not screen-space steering keys. */
public record PaddleBoat(boolean left, boolean right) implements InboundEvent { }
