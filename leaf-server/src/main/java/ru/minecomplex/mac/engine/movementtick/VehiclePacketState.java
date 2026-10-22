package ru.minecomplex.mac.engine.movementtick;

import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.world.phys.Vec3;
import ru.minecomplex.mac.util.Tri;
import java.util.Objects;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * Resolved root-entity snapshot at the send phase. Identity/authority and interpolation belong to one hypothesis.
 */
public record VehiclePacketState(Object generation, Tri localAuthority, Vec3d position, float yaw, float pitch, Target target, boolean interpolating, boolean onGround) {

    public record Target(Vec3d position, float yaw, float pitch) {
    }

    public VehiclePacketState {
        Objects.requireNonNull(generation);
        Objects.requireNonNull(localAuthority);
        Objects.requireNonNull(position);
        if (interpolating && target == null)
            throw new IllegalArgumentException("Active interpolation needs a target");
    }

    /**
     * Native ServerboundMoveVehiclePacket.fromEntity at the selected interpolation target.
     */
    public ServerboundMoveVehiclePacket packet() {
        Vec3d sentPosition = position;
        float sentYaw = yaw, sentPitch = pitch;
        if (target != null) {
            sentPosition = target.position;
            sentYaw = target.yaw;
            sentPitch = target.pitch;
        }
        return ru.minecomplex.mac.packet.Packets.vehicleMove(new Vec3(sentPosition.x(), sentPosition.y(), sentPosition.z()), sentYaw, sentPitch, onGround);
    }
}
