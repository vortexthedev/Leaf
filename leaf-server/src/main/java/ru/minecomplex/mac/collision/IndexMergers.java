package ru.minecomplex.mac.collision;


/**
 * Ports of vanilla's {@code IndexMerger} implementations ({@code IndirectMerger}, {@code NonOverlappingMerger},
 * {@code IdenticalMerger}, {@code DiscreteCubeMerger}) and {@code Shapes.createIndexMerger}.
 */
final class IndexMergers {
    private IndexMergers() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    static interface IndexConsumer {
        boolean merge(int first, int second, int result);
    }

    static interface IndexMerger {
        /** @return false as soon as the consumer returns false */
        boolean forMergedIndexes(IndexConsumer consumer);

        int size();
        Coords coords();
    }

    /** {@code Shapes.createIndexMerger}. */
    static IndexMerger create(int cost, Coords first, Coords second, boolean firstOnlyMatters, boolean secondOnlyMatters) {
        int firstSize = first.size() - 1;
        int secondSize = second.size() - 1;
        if (first instanceof Coords.Cube && second instanceof Coords.Cube) {
            long lcm = lcm(firstSize, secondSize);
            if (cost * lcm <= 256L) {
                return discreteCube(firstSize, secondSize);
            }
        }
        if (first.get(firstSize) < second.get(0) - 1.0E-7) {
            return nonOverlapping(first, second, false);
        } else if (second.get(secondSize) < first.get(0) - 1.0E-7) {
            return nonOverlapping(second, first, true);
        } else {
            return firstSize == secondSize && first.sameValues(second)
                    ? identical(first)
                    : indirect(first, second, firstOnlyMatters, secondOnlyMatters);
        }
    }

    static long lcm(int a, int b) {
        return (long) a * (b / gcd(a, b));
    }

    /** {@code IntMath.gcd} for non-negative arguments. */
    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    private static IndexMerger identical(Coords coords) {
        return new IndexMerger() {
            @Override
            public boolean forMergedIndexes(IndexConsumer consumer) {
                int last = coords.size() - 1;
                for (int i = 0; i < last; i++) {
                    if (!consumer.merge(i, i, i)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public int size() {
                return coords.size();
            }
            public Coords coords() { return coords; }
        };
    }

    private static IndexMerger nonOverlapping(Coords lower, Coords upper, boolean swap) {
        return new IndexMerger() {
            @Override
            public boolean forMergedIndexes(IndexConsumer consumer) {
                IndexConsumer target = swap ? (a, b, r) -> consumer.merge(b, a, r) : consumer;
                int lowerSize = lower.size();
                for (int i = 0; i < lowerSize; i++) {
                    if (!target.merge(i, -1, i)) {
                        return false;
                    }
                }
                int upperLast = upper.size() - 1;
                for (int i = 0; i < upperLast; i++) {
                    if (!target.merge(lowerSize - 1, i, lowerSize + i)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public int size() {
                return lower.size() + upper.size();
            }
            public Coords coords() {
                double[] result = new double[size()];
                for (int i = 0; i < result.length; i++) result[i] = i < lower.size() ? lower.get(i) : upper.get(i - lower.size());
                return new Coords.Array(result);
            }
        };
    }

    private static IndexMerger discreteCube(int firstSize, int secondSize) {
        int resultParts = (int) lcm(firstSize, secondSize);
        int gcd = gcd(firstSize, secondSize);
        int firstDiv = firstSize / gcd;
        int secondDiv = secondSize / gcd;
        return new IndexMerger() {
            @Override
            public boolean forMergedIndexes(IndexConsumer consumer) {
                for (int i = 0; i < resultParts; i++) {
                    if (!consumer.merge(i / secondDiv, i / firstDiv, i)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public int size() {
                return resultParts + 1;
            }
            public Coords coords() { return new Coords.Cube(resultParts); }
        };
    }

    /** {@code IndirectMerger}: merges two sorted coordinate lists, coordinates within 1e-7 collapse. */
    private static IndexMerger indirect(Coords first, Coords second, boolean firstOnlyMatters, boolean secondOnlyMatters) {
        double lastValue = Double.NaN;
        int firstCount = first.size();
        int secondCount = second.size();
        int capacity = firstCount + secondCount;
        int[] firstIndices = new int[capacity];
        int[] secondIndices = new int[capacity];
        double[] coordinates = new double[capacity];
        boolean skipFirstOnly = !firstOnlyMatters;
        boolean skipSecondOnly = !secondOnlyMatters;
        int length = 0;
        int firstIndex = 0;
        int secondIndex = 0;
        while (true) {
            boolean firstDone = firstIndex >= firstCount;
            boolean secondDone = secondIndex >= secondCount;
            if (firstDone && secondDone) {
                break;
            }
            boolean takeFirst = !firstDone && (secondDone || first.get(firstIndex) < second.get(secondIndex) + 1.0E-7);
            if (takeFirst) {
                firstIndex++;
                if (skipFirstOnly && (secondIndex == 0 || secondDone)) {
                    continue;
                }
            } else {
                secondIndex++;
                if (skipSecondOnly && (firstIndex == 0 || firstDone)) {
                    continue;
                }
            }
            int previousFirst = firstIndex - 1;
            int previousSecond = secondIndex - 1;
            double value = takeFirst ? first.get(previousFirst) : second.get(previousSecond);
            if (!(lastValue >= value - 1.0E-7)) {
                firstIndices[length] = previousFirst;
                secondIndices[length] = previousSecond;
                coordinates[length] = value;
                length++;
                lastValue = value;
            } else {
                firstIndices[length - 1] = previousFirst;
                secondIndices[length - 1] = previousSecond;
            }
        }
        int resultLength = Math.max(1, length);
        return new IndexMerger() {
            @Override
            public boolean forMergedIndexes(IndexConsumer consumer) {
                int last = resultLength - 1;
                for (int i = 0; i < last; i++) {
                    if (!consumer.merge(firstIndices[i], secondIndices[i], i)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public int size() {
                return resultLength;
            }
            public Coords coords() { return new Coords.Array(java.util.Arrays.copyOf(coordinates, resultLength)); }
        };
    }
}
