package ru.minecomplex.mac.listener;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;



import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import java.util.List;
import java.util.Set;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.engine.movementtick.VehiclePacketState;
import ru.minecomplex.mac.util.math.Vec3d;

/** ClientPacketListener.handleMoveVehicle routing and payload state. This handler does not simulate a tick. */
public final class VehicleCorrectionHandler {
    private VehicleCorrectionHandler() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    /** A snap also requires the owning vehicle engine to update its old position/rotation like Entity.absSnapTo. */
    public static record Result(VehiclePacketState root, boolean snapped, List<ServerboundMoveVehiclePacket> replies, Set<Unmodeled> unmodeled) { }
    public static Result apply(ClientboundMoveVehiclePacket correction, boolean passenger, VehiclePacketState root) {
        if (!passenger) return new Result(root, false, List.of(), Set.of());
        if (root == null || root.localAuthority() == Tri.EITHER) return new Result(root, false, List.of(), Set.of(Unmodeled.RIDING));
        if (root.localAuthority() == Tri.FALSE) return new Result(root, false, List.of(), Set.of());
        var clientPosition = root.packet().movingTo().position();
        var target = correction.movingTo().position();
        double dx = target.x() - clientPosition.x(), dy = target.y() - clientPosition.y(), dz = target.z() - clientPosition.z();
        boolean snapped = Math.sqrt(dx * dx + dy * dy + dz * dz) > 1.0E-5F;
        if (snapped) {
            Vec3d position = new Vec3d(Math.clamp(target.x(), -3.0E7, 3.0E7), target.y(), Math.clamp(target.z(), -3.0E7, 3.0E7));
            float yaw = correction.movingTo().yRot() % 360.0F, pitch = Math.clamp(correction.movingTo().xRot(), -90.0F, 90.0F) % 360.0F;
            if (!Float.isFinite(yaw)) yaw = root.yaw();
            if (!Float.isFinite(pitch)) pitch = root.pitch();
            root = new VehiclePacketState(root.generation(), root.localAuthority(), position, yaw, pitch, root.interpolating() ? null : root.target(), false, root.onGround());
        }
        return new Result(root, snapped, List.of(root.packet()), Set.of());
    }
}
