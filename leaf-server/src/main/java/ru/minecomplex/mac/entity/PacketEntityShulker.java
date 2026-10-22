package ru.minecomplex.mac.entity;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.replica.CompensatedEntities;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

/** Another shulker: placed at once on its block ({@link ShulkerBody}), its box opens with its peek. */
public final class PacketEntityShulker extends PacketEntityFollowed {
    ShulkerBody shulker = new ShulkerBody();

    public PacketEntityShulker(CompensatedEntities owner, int id, String type, ClientboundAddEntityPacket spawn) {
        super(owner, id, type, false, spawn, new Object());
    }

    private PacketEntityShulker(CompensatedEntities owner, PacketEntityShulker source) {
        super(owner, source.id(), source.type(), false, source.spawn(), source.generation());
    }

    @Override
    public PacketEntityShulker copy(CompensatedEntities owner) {
        var copy = copyFollowed(new PacketEntityShulker(owner, this));
        copy.shulker = shulker.copy();
        return copy;
    }

    @Override
    public Kind kind() {
        return Kind.SHULKER;
    }

    @Override
    public Vec3d position() {
        return shulker.position;
    }

    @Override
    public boolean hasBody() {
        return true;
    }

    @Override
    public Aabb box() {
        return shulker.box;
    }
}
