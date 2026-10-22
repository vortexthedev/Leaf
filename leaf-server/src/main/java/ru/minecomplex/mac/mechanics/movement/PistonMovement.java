package ru.minecomplex.mac.mechanics.movement;

import java.util.Arrays;
import ru.minecomplex.mac.collision.Axis;
import ru.minecomplex.mac.util.math.VanillaMath;
import ru.minecomplex.mac.util.math.Vec3d;

/** Entity.limitPistonMovement. Accumulators belong to one entity and one chosen client game time. */
public final class PistonMovement {
    private final double[] deltas = new double[3];
    private long gameTime;
    public PistonMovement copy() { var copy=new PistonMovement(); System.arraycopy(deltas,0,copy.deltas,0,3);copy.gameTime=gameTime;return copy; }
    public Vec3d limit(Vec3d vector,long now) {
        if (vector.lengthSqr() <= 1.0E-7) return vector;
        if (now != gameTime) { Arrays.fill(deltas,0.0);gameTime=now; }
        for (Axis axis : Axis.values()) if (vector.get(axis) != 0.0) {
            double amount=vector.get(axis), value=VanillaMath.clamp(amount+deltas[axis.ordinal()],-0.51,0.51);
            amount=value-deltas[axis.ordinal()];deltas[axis.ordinal()]=value;
            // javap: ldc2_w 9.999999747378752E-6; this is a widened FLOAT literal.
            return Math.abs(amount) <= 1.0E-5F ? Vec3d.ZERO : Vec3d.ZERO.with(axis,amount);
        }
        return Vec3d.ZERO;
    }
}
