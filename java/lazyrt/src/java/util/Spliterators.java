package java.util;

public final class Spliterators {
    private Spliterators() {
    }

    private static final Spliterator<Object> NO_REFS = new Empties.Refs();
    private static final Spliterator.OfInt NO_INTS = new Empties.Ints();
    private static final Spliterator.OfLong NO_LONGS = new Empties.Longs();
    private static final Spliterator.OfDouble NO_DOUBLES = new Empties.Doubles();

    /** A concurrent source does not promise its size, so SIZED is not added to it. */
    private static int sized(int characteristics) {
        return (characteristics & Spliterator.CONCURRENT) != 0 ? characteristics
                : characteristics | ArraySplits.SIZES;
    }

    // ---- empty ----

    @SuppressWarnings("unchecked")
    public static <T> Spliterator<T> emptySpliterator() { return (Spliterator<T>) NO_REFS; }

    public static Spliterator.OfInt emptyIntSpliterator() { return NO_INTS; }

    public static Spliterator.OfLong emptyLongSpliterator() { return NO_LONGS; }

    public static Spliterator.OfDouble emptyDoubleSpliterator() { return NO_DOUBLES; }

    // ---- arrays ----

    public static <T> Spliterator<T> spliterator(Object[] array, int additionalCharacteristics) {
        return ArraySplits.<T>refs(array, 0, array.length, additionalCharacteristics);
    }

    public static <T> Spliterator<T> spliterator(Object[] array, int fromIndex, int toIndex,
            int additionalCharacteristics) {
        return ArraySplits.<T>refs(array, fromIndex, toIndex, additionalCharacteristics);
    }

    public static Spliterator.OfInt spliterator(int[] array, int additionalCharacteristics) {
        return ArraySplits.ints(array, 0, array.length, additionalCharacteristics);
    }

    public static Spliterator.OfInt spliterator(int[] array, int fromIndex, int toIndex,
            int additionalCharacteristics) {
        return ArraySplits.ints(array, fromIndex, toIndex, additionalCharacteristics);
    }

    public static Spliterator.OfLong spliterator(long[] array, int additionalCharacteristics) {
        return ArraySplits.longs(array, 0, array.length, additionalCharacteristics);
    }

    public static Spliterator.OfLong spliterator(long[] array, int fromIndex, int toIndex,
            int additionalCharacteristics) {
        return ArraySplits.longs(array, fromIndex, toIndex, additionalCharacteristics);
    }

    public static Spliterator.OfDouble spliterator(double[] array, int additionalCharacteristics) {
        return ArraySplits.doubles(array, 0, array.length, additionalCharacteristics);
    }

    public static Spliterator.OfDouble spliterator(double[] array, int fromIndex, int toIndex,
            int additionalCharacteristics) {
        return ArraySplits.doubles(array, fromIndex, toIndex, additionalCharacteristics);
    }

    // ---- collections and iterators ----

    public static <T> Spliterator<T> spliterator(Collection<? extends T> c, int characteristics) {
        return new Late<T>(Objects.requireNonNull(c), sized(characteristics), null);
    }

    public static <T> Spliterator<T> spliterator(Iterator<? extends T> iterator, long size, int characteristics) {
        return new IterSplits.Refs<T>(Objects.requireNonNull(iterator), size, sized(characteristics));
    }

    public static Spliterator.OfInt spliterator(PrimitiveIterator.OfInt iterator, long size, int characteristics) {
        return new IterSplits.Ints(Objects.requireNonNull(iterator), size, sized(characteristics));
    }

    public static Spliterator.OfLong spliterator(PrimitiveIterator.OfLong iterator, long size, int characteristics) {
        return new IterSplits.Longs(Objects.requireNonNull(iterator), size, sized(characteristics));
    }

    public static Spliterator.OfDouble spliterator(PrimitiveIterator.OfDouble iterator, long size,
            int characteristics) {
        return new IterSplits.Doubles(Objects.requireNonNull(iterator), size, sized(characteristics));
    }

    public static <T> Spliterator<T> spliteratorUnknownSize(Iterator<? extends T> iterator, int characteristics) {
        return new IterSplits.Refs<T>(Objects.requireNonNull(iterator), Long.MAX_VALUE, characteristics & ~ArraySplits.SIZES);
    }

    public static Spliterator.OfInt spliteratorUnknownSize(PrimitiveIterator.OfInt iterator, int characteristics) {
        return new IterSplits.Ints(Objects.requireNonNull(iterator), Long.MAX_VALUE, characteristics & ~ArraySplits.SIZES);
    }

    public static Spliterator.OfLong spliteratorUnknownSize(PrimitiveIterator.OfLong iterator,
            int characteristics) {
        return new IterSplits.Longs(Objects.requireNonNull(iterator), Long.MAX_VALUE, characteristics & ~ArraySplits.SIZES);
    }

    public static Spliterator.OfDouble spliteratorUnknownSize(PrimitiveIterator.OfDouble iterator,
            int characteristics) {
        return new IterSplits.Doubles(Objects.requireNonNull(iterator), Long.MAX_VALUE, characteristics & ~ArraySplits.SIZES);
    }

    // ---- spliterator to iterator ----

    public static <T> Iterator<T> iterator(Spliterator<? extends T> spliterator) {
        return new Walkers.Refs<T>(Objects.requireNonNull(spliterator));
    }

    public static PrimitiveIterator.OfInt iterator(Spliterator.OfInt spliterator) {
        return new Walkers.Ints(Objects.requireNonNull(spliterator));
    }

    public static PrimitiveIterator.OfLong iterator(Spliterator.OfLong spliterator) {
        return new Walkers.Longs(Objects.requireNonNull(spliterator));
    }

    public static PrimitiveIterator.OfDouble iterator(Spliterator.OfDouble spliterator) {
        return new Walkers.Doubles(Objects.requireNonNull(spliterator));
    }

    // ---- bases for user spliterators ----

    public abstract static class AbstractSpliterator<T> implements Spliterator<T> {
        private final int ch;
        private int batch;
        /** The estimate, counted down as splits hand elements over; the late-binding subclasses set it too. */
        long remaining;

        protected AbstractSpliterator(long est, int additionalCharacteristics) {
            remaining = est;
            ch = (additionalCharacteristics & Spliterator.SIZED) != 0
                    ? additionalCharacteristics | Spliterator.SUBSIZED : additionalCharacteristics;
        }

        @Override
        public Spliterator<T> trySplit() {
            batch += 1024;
            int want = (int) Math.min(batch, remaining);
            Spliterator<T> part = want > 0 ? Batches.refs(this, want, characteristics()) : null;
            if (part != null && remaining != Long.MAX_VALUE) {
                remaining = Math.max(0, remaining - part.estimateSize());
            }
            return part;
        }

        @Override
        public long estimateSize() { return remaining; }

        @Override
        public int characteristics() { return ch; }
    }

    public abstract static class AbstractIntSpliterator implements Spliterator.OfInt {
        private final int ch;
        private int batch;
        long remaining;

        protected AbstractIntSpliterator(long est, int additionalCharacteristics) {
            remaining = est;
            ch = (additionalCharacteristics & Spliterator.SIZED) != 0
                    ? additionalCharacteristics | Spliterator.SUBSIZED : additionalCharacteristics;
        }

        @Override
        public Spliterator.OfInt trySplit() {
            batch += 1024;
            int want = (int) Math.min(batch, remaining);
            Spliterator.OfInt part = want > 0 ? Batches.ints(this, want, characteristics()) : null;
            if (part != null && remaining != Long.MAX_VALUE) {
                remaining = Math.max(0, remaining - part.estimateSize());
            }
            return part;
        }

        @Override
        public long estimateSize() { return remaining; }

        @Override
        public int characteristics() { return ch; }
    }

    public abstract static class AbstractLongSpliterator implements Spliterator.OfLong {
        private final int ch;
        private int batch;
        long remaining;

        protected AbstractLongSpliterator(long est, int additionalCharacteristics) {
            remaining = est;
            ch = (additionalCharacteristics & Spliterator.SIZED) != 0
                    ? additionalCharacteristics | Spliterator.SUBSIZED : additionalCharacteristics;
        }

        @Override
        public Spliterator.OfLong trySplit() {
            batch += 1024;
            int want = (int) Math.min(batch, remaining);
            Spliterator.OfLong part = want > 0 ? Batches.longs(this, want, characteristics()) : null;
            if (part != null && remaining != Long.MAX_VALUE) {
                remaining = Math.max(0, remaining - part.estimateSize());
            }
            return part;
        }

        @Override
        public long estimateSize() { return remaining; }

        @Override
        public int characteristics() { return ch; }
    }

    public abstract static class AbstractDoubleSpliterator implements Spliterator.OfDouble {
        private final int ch;
        private int batch;
        long remaining;

        protected AbstractDoubleSpliterator(long est, int additionalCharacteristics) {
            remaining = est;
            ch = (additionalCharacteristics & Spliterator.SIZED) != 0
                    ? additionalCharacteristics | Spliterator.SUBSIZED : additionalCharacteristics;
        }

        @Override
        public Spliterator.OfDouble trySplit() {
            batch += 1024;
            int want = (int) Math.min(batch, remaining);
            Spliterator.OfDouble part = want > 0 ? Batches.doubles(this, want, characteristics()) : null;
            if (part != null && remaining != Long.MAX_VALUE) {
                remaining = Math.max(0, remaining - part.estimateSize());
            }
            return part;
        }

        @Override
        public long estimateSize() { return remaining; }

        @Override
        public int characteristics() { return ch; }
    }
}
