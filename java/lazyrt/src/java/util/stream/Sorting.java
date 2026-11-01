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

    /** The capacity to gather into: the announced size, or a small start when it is unknown. */
    private static int room(long size) {
        if (size < 0) {
            return 16;
        }
        if (size >= Flow.MAX_ARRAY) {
            throw new IllegalArgumentException("Stream size exceeds max array size");
        }
        return (int) size;
    }

    /** Grows a gathering array that has no announced size. */
    private static int grown(int n) { return Math.max(16, n * 2); }

    static final class Refs extends Relay.Pass {
        private final Comparator<Object> order;
        private Object[] items;
        private int n;
        private boolean fixed;

        Refs(Relay next, Comparator<Object> order) {
            super(next);
            this.order = order;
        }

        @Override
        void start(long size) {
            items = new Object[room(size)];
            fixed = size >= 0;
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

        Ints(Relay next) { super(next); }

        @Override
        void start(long size) {
            items = new int[room(size)];
            fixed = size >= 0;
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

        Longs(Relay next) { super(next); }

        @Override
        void start(long size) {
            items = new long[room(size)];
            fixed = size >= 0;
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

        Doubles(Relay next) { super(next); }

        @Override
        void start(long size) {
            items = new double[room(size)];
            fixed = size >= 0;
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
