package ru.minecomplex.mac.entity.interpolation;

/** Boat's three-step interpolation; its tick phase is selected by BoatPhysics. */
public final class BoatInterpolation extends LinearEntityInterpolation {
    @Override public BoatInterpolation copy() {
        var copy=new BoatInterpolation(); copyTo(copy); return copy;
    }
}
