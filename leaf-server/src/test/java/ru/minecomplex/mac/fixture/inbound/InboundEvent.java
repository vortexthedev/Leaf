package ru.minecomplex.mac.fixture.inbound;

/**
 * A serverbound packet as sent by the client, normalised from the native layer (client protocol, wire order).
 * Received values are {@code CLIENT_REPORTED}: untrusted claims of the client. Prediction results may
 * use the same payload types; their owning result supplies provenance and must not be treated as received events.
 */
public sealed interface InboundEvent permits
        TickEnd,
        Pong,
        Movement,
        VehicleMovement,
        PaddleBoat,
        TeleportAccept,
        PlayerInput,
        PlayerCommand,
        PlayerAbilities,
        HeldSlot,
        PlayerAction,
        UseItem,
        UseItemOn,
        InteractEntity,
        Attack,
        Swing,
        ContainerClosed,
        ContainerClick,
        CreativeSlot,
        PlayerLoaded,
        KeepAlive,
        ChunkBatchReceived,
        OtherInbound {

    /**
     * True for packets a vanilla client sends from its netty thread (not ordered with main-thread packets and
     * possibly appearing inside a tick on the wire).
     */
    default boolean nettyThread() {
        return false;
    }

    /**
     * True for packets a vanilla client may send from its input handling (screen callbacks while it polls window
     * events), between one render frame's ticks and the next frame's packet drain: container clicks and closes, creative
     * slots, chat and the other screen packets. They precede the drain whose pongs come after them, so a pong after them
     * is not a response inside a tick. Packets only {@code Minecraft.tick()} produces return false.
     */
    default boolean betweenTicks() {
        return false;
    }
}
