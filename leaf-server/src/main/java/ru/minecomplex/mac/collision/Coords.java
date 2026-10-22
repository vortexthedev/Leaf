package ru.minecomplex.mac.collision;

/**
 * The coordinate lists of a voxel shape axis: {@code DoubleArrayList}, {@code CubePointRange} and
 * {@code OffsetDoubleList}. Values are computed exactly as vanilla does (e.g. {@code delegate + offset} per access).
 */
abstract sealed class Coords {
    abstract int size();

    abstract double get(int index);

    /** {@code AbstractDoubleList.equals}: same size and every element {@code ==}. */
    boolean sameValues(Coords other) {
        int size = size();
        if (size != other.size()) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            if (get(i) != other.get(i)) {
                return false;
            }
        }
        return true;
    }

    static final class Array extends Coords {
        private final double[] values;

        Array(double[] values) {
            this.values = values;
        }

        @Override
        int size() {
            return values.length;
        }

        @Override
        double get(int index) {
            return values[index];
        }
    }

    /** {@code CubePointRange}: {@code i / parts}, {@code parts + 1} values. */
    static final class Cube extends Coords {
        private final int parts;

        Cube(int parts) {
            if (parts <= 0) {
                throw new IllegalArgumentException("Need at least 1 part");
            }
            this.parts = parts;
        }

        @Override
        int size() {
            return parts + 1;
        }

        @Override
        double get(int index) {
            return (double) index / parts;
        }
    }

    /** {@code OffsetDoubleList}. */
    static final class Offset extends Coords {
        private final Coords delegate;
        private final double offset;

        Offset(Coords delegate, double offset) {
            this.delegate = delegate;
            this.offset = offset;
        }

        @Override
        int size() {
            return delegate.size();
        }

        @Override
        double get(int index) {
            return delegate.get(index) + offset;
        }
    }
}
