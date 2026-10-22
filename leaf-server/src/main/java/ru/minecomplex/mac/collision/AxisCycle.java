package ru.minecomplex.mac.collision;

/** Port of {@code net.minecraft.core.AxisCycle} (unchanged 1.21.4 – 26.3). */
enum AxisCycle {
    NONE {
        @Override
        int cycle(int x, int y, int z, Axis axis) {
            return axis.choose(x, y, z);
        }

        @Override
        Axis cycle(Axis axis) {
            return axis;
        }

        @Override
        AxisCycle inverse() {
            return this;
        }
    },
    FORWARD {
        @Override
        int cycle(int x, int y, int z, Axis axis) {
            return axis.choose(z, x, y);
        }

        @Override
        Axis cycle(Axis axis) {
            return Axis.VALUES[Math.floorMod(axis.ordinal() + 1, 3)];
        }

        @Override
        AxisCycle inverse() {
            return BACKWARD;
        }
    },
    BACKWARD {
        @Override
        int cycle(int x, int y, int z, Axis axis) {
            return axis.choose(y, z, x);
        }

        @Override
        Axis cycle(Axis axis) {
            return Axis.VALUES[Math.floorMod(axis.ordinal() - 1, 3)];
        }

        @Override
        AxisCycle inverse() {
            return FORWARD;
        }
    };

    private static final AxisCycle[] VALUES = values();

    abstract int cycle(int x, int y, int z, Axis axis);

    abstract Axis cycle(Axis axis);

    abstract AxisCycle inverse();

    static AxisCycle between(Axis from, Axis to) {
        return VALUES[Math.floorMod(to.ordinal() - from.ordinal(), 3)];
    }
}
