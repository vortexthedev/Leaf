package ru.minecomplex.mac.entity;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import ru.minecomplex.mac.replica.CompensatedEntities;
import ru.minecomplex.mac.util.Tri;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

/**
 * A living entity the client constructs ({@code LivingEntity}): its stored dimensions ({@link LivingShape}) and delta
 * movement. Mounts the local player may drive have their own classes.
 */
public class PacketEntityLiving extends PacketEntityMoving {
    LivingShape shape;
    /** Controller facts supplied by the local player's inventory/metadata hypothesis for living mounts. */
    Tri localController = Tri.EITHER;
    /** This frame may have selected the steering item before its wire handlers ran. */
    EntityMotion earlyController;
    boolean earlyControllerHandled;

    public PacketEntityLiving(CompensatedEntities owner, int id, String type, boolean player, ClientboundAddEntityPacket spawn) {
        super(owner, id, type, player, spawn, new Object());
    }

    protected PacketEntityLiving(CompensatedEntities owner, PacketEntityLiving source) {
        super(owner, source.id(), source.type(), source.player(), source.spawn(), source.generation());
    }

    @Override
    public PacketEntityLiving copy(CompensatedEntities owner) {
        return copyLiving(new PacketEntityLiving(owner, this));
    }

    /** Copies the living state into a copy of the same generation. */
    protected final <T extends PacketEntityLiving> T copyLiving(T copy) {
        copyMoving(copy);
        PacketEntityLiving target = copy;
        target.shape = shape == null ? null : shape.copy();
        target.localController = localController;
        target.earlyController = earlyController == null ? null : earlyController.copy();
        target.earlyControllerHandled = earlyControllerHandled;
        return copy;
    }

    @Override
    public Kind kind() {
        return Kind.LIVING;
    }

    public LivingShape shape() {
        return shape;
    }
}
