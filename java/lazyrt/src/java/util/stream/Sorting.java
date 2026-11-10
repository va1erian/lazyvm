package java.util.stream;

import java.util.Arrays;
import java.util.Comparator;

/**
 * The barrier stages: all elements are gathered, sorted when the line finishes and then pushed on. Gathering
 * into an array of the announced size is what makes a lying source show (docs/design/streams.md T5, T6).
 */
final class Sorting {
    private Sorting() {
    }

    /**
     * The capacity to gather into. An announced size is exact only while the pipeline is still SIZED here; after a
     * limit or skip it is a hint (and still tells a size that cannot be an array), so the array may grow.
     */
    private static int room(long size, boolean exact) {
        if (size < 0) {
            return 16;
        }
        if (size >= Flow.MAX_ARRAY) {
            throw new IllegalArgumentException("Stream size exceeds max array size");
        }
        return exact ? (int) size : (int) Math.min(size, 1024);
    }

    /** Grows a gathering array that has no announced size. */
    private static int grown(int n) { return Math.max(16, n * 2); }

    static final class Refs extends Relay.Pass {
        private final Comparator<Object> order;
        private Object[] items;
        private int n;
        private boolean fixed;
        private final boolean exact;

        Refs(Relay next, Comparator<Object> order, boolean exact) {
            super(next);
            this.exact = exact;
            this.order = order;
        }

        @Override
        void start(long size) {
            items = new Object[room(size, exact)];
            fixed = exact && size >= 0;
        }

        @Override
        public void accept(Object x) {
            if (n == items.length && !fixed) {
                items = Arrays.copyOf(items, grown(n));
            }
            items[n++] = x;
        }

        @Override
        void finish() {
            Object[] sorted = n == items.length ? items : Arrays.copyOf(items, n);
            Arrays.sort(sorted, order);
            next.start(n);
            for (Object x : sorted) {
                if (watching && next.halted()) {
                    break;
                }
                next.accept(x);
            }
            next.finish();
        }

        @Override
        boolean halted() { return false; }
    }

    static final class Ints extends Relay.Pass {
        private int[] items;
        private int n;
        private boolean fixed;
        private final boolean exact;

        Ints(Relay next, boolean exact) {
            super(next);
            this.exact = exact;
        }

        @Override
        void start(long size) {
            items = new int[room(size, exact)];
            fixed = exact && size >= 0;
        }

        @Override
        public void accept(int x) {
            if (n == items.length && !fixed) {
                items = Arrays.copyOf(items, grown(n));
            }
            items[n++] = x;
        }

        @Override
        void finish() {
            Arrays.sort(items, 0, n);
            next.start(n);
            for (int i = 0; i < n && !(watching && next.halted()); i++) {
                next.accept(items[i]);
            }
            next.finish();
        }

        @Override
        boolean halted() { return false; }
    }

    static final class Longs extends Relay.Pass {
        private long[] items;
        private int n;
        private boolean fixed;
        private final boolean exact;

        Longs(Relay next, boolean exact) {
            super(next);
            this.exact = exact;
        }

        @Override
        void start(long size) {
            items = new long[room(size, exact)];
            fixed = exact && size >= 0;
        }

        @Override
        public void accept(long x) {
            if (n == items.length && !fixed) {
                items = Arrays.copyOf(items, grown(n));
            }
            items[n++] = x;
        }

        @Override
        void finish() {
            Arrays.sort(items, 0, n);
            next.start(n);
            for (int i = 0; i < n && !(watching && next.halted()); i++) {
                next.accept(items[i]);
            }
            next.finish();
        }

        @Override
        boolean halted() { return false; }
    }

    static final class Doubles extends Relay.Pass {
        private double[] items;
        private int n;
        private boolean fixed;
        private final boolean exact;

        Doubles(Relay next, boolean exact) {
            super(next);
            this.exact = exact;
        }

        @Override
        void start(long size) {
            items = new double[room(size, exact)];
            fixed = exact && size >= 0;
        }

        @Override
        public void accept(double x) {
            if (n == items.length && !fixed) {
                items = Arrays.copyOf(items, grown(n));
            }
            items[n++] = x;
        }

        @Override
        void finish() {
            Arrays.sort(items, 0, n);
            next.start(n);
            for (int i = 0; i < n && !(watching && next.halted()); i++) {
                next.accept(items[i]);
            }
            next.finish();
        }

        @Override
        boolean halted() { return false; }
    }
}
