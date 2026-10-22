package ru.minecomplex.mac.entity;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.replica.CompensatedEntities;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

/**
 * An entity that may push the player or collide with a vehicle but is not simulated here (minecarts, living types the
 * client cannot construct here, the ender dragon): it keeps the positions its packets named, a conservative reach used
 * to report what is not modelled.
 */
public final class PacketEntityPositions extends PacketEntityFollowed {
    /** Packet positions kept for its reach. */
    public static final int RECENT = 8;

    /** {@code getPositionCodec().getBase()}. */
    Vec3d codecBase;
    /** Packet positions, newest last. */
    Vec3d[] targets;

    public PacketEntityPositions(CompensatedEntities owner, int id, String type, boolean player, ClientboundAddEntityPacket spawn) {
        super(owner, id, type, player, spawn, new Object());
    }

    private PacketEntityPositions(CompensatedEntities owner, PacketEntityPositions source) {
        super(owner, source.id(), source.type(), source.player(), source.spawn(), source.generation());
    }

    @Override
    public PacketEntityPositions copy(CompensatedEntities owner) {
        var copy = copyFollowed(new PacketEntityPositions(owner, this));
        copy.codecBase = codecBase;
        copy.targets = targets;
        return copy;
    }

    @Override
    public Kind kind() {
        return Kind.PUSHABLE;
    }

    @Override
    public Vec3d position() {
        return targets[targets.length - 1];
    }

    /** A new packet position: the last {@link #RECENT} are kept. */
    void push(Vec3d target) {
        int keep = Math.min(targets.length, RECENT - 1);
        Vec3d[] next = new Vec3d[keep + 1];
        System.arraycopy(targets, targets.length - keep, next, 0, keep);
        next[keep] = target;
        targets = next;
    }
}
