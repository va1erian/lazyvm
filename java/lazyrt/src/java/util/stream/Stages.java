package java.util.stream;

import java.util.HashSet;
import java.util.function.Function;

/** The stages that do not depend on the element type: limit, skip, distinct and flatMap. */
final class Stages {
    private Stages() {
    }

    /** Passes the first n elements and then halts the line. */
    static final class Limit extends Relay.Narrow {
        private final long limit;
        private long left;

        Limit(Relay next, long n) {
            super(next);
            this.limit = n;
            this.left = n;
        }

        private boolean take() {
            if (left <= 0) {
                return false;
            }
            left--;
            return true;
        }

        /** A limit leaves at most n elements of however many were announced. */
        @Override
        void start(long size) { next.start(size < 0 ? -1 : Math.min(size, limit)); }

        @Override
        boolean halted() { return left <= 0 || next.halted(); }

        @Override
        public void accept(Object x) {
            if (take()) {
                next.accept(x);
            }
        }

        @Override
        public void accept(int x) {
            if (take()) {
                next.accept(x);
            }
        }

        @Override
        public void accept(long x) {
            if (take()) {
                next.accept(x);
            }
        }

        @Override
        public void accept(double x) {
            if (take()) {
                next.accept(x);
            }
        }
    }

    /** Drops the first n elements. */
    static final class Skip extends Relay.Narrow {
        private long left;

        Skip(Relay next, long n) {
            super(next);
            this.dropped = n;
            this.left = n;
        }

        private final long dropped;

        /** A skip leaves what remains of the announced count after the dropped ones. */
        @Override
        void start(long size) { next.start(size < 0 ? -1 : Math.max(0, size - dropped)); }

        private boolean pass() {
            if (left > 0) {
                left--;
                return false;
            }
            return true;
        }

        @Override
        public void accept(Object x) {
            if (pass()) {
                next.accept(x);
            }
        }

        @Override
        public void accept(int x) {
            if (pass()) {
                next.accept(x);
            }
        }

        @Override
        public void accept(long x) {
            if (pass()) {
                next.accept(x);
            }
        }

        @Override
        public void accept(double x) {
            if (pass()) {
                next.accept(x);
            }
        }
    }

    /** The layer of skip(n). Directly behind the source it lets a range jump instead of visiting each value. */
    static final class Skipping implements Relay.Layer {
        private final long n;

        Skipping(long n) { this.n = n; }

        @Override
        public Relay before(Relay next) { return new Skip(next, n); }

        Relay jump(Relay next, Pipe pipe) { return new Skip(next, Ranges.skipAhead(pipe.source(), n)); }
    }

    /** Lets each value through once, remembering what it has seen. */
    static final class Hashed extends Relay.Narrow {
        private final HashSet<Object> seen = new HashSet<>();

        Hashed(Relay next) { super(next); }

        @Override
        public void accept(Object x) {
            if (seen.add(x)) {
                next.accept(x);
            }
        }

        @Override
        public void accept(int x) {
            if (seen.add(x)) {
                next.accept(x);
            }
        }

        @Override
        public void accept(long x) {
            if (seen.add(x)) {
                next.accept(x);
            }
        }

        @Override
        public void accept(double x) {
            if (seen.add(x)) {
                next.accept(x);
            }
        }
    }

    /**
     * distinct() of a sorted stream compares each value with the one passed before it (rule SP8). The first null
     * passes and makes the line forget what came before, so the next value passes whatever it is; later nulls
     * are dropped and change nothing.
     */
    static final class Neighbors extends Relay.Narrow {
        private static final Object NOTHING = new Object();
        private Object previous = NOTHING;
        private boolean nullPassed;

        Neighbors(Relay next) { super(next); }

        @Override
        public void accept(Object x) {
            if (x == null) {
                if (nullPassed) {
                    return;
                }
                nullPassed = true;
                previous = NOTHING;
            } else if (previous != NOTHING && x.equals(previous)) {
                return;
            } else {
                previous = x;
            }
            next.accept(x);
        }
    }

    /** Makes a stream of each value, pushes it on and closes it, even when that fails. */
    static final class Flat extends Relay.Narrow {
        private final Function<Object, ? extends BaseStream<?, ?>> mapper;

        Flat(Relay next, Function<Object, ? extends BaseStream<?, ?>> mapper) {
            super(next);
            this.mapper = mapper;
        }

        private void open(BaseStream<?, ?> inner) {
            if (inner != null) {
                Failures.thenClose(inner, () -> Flow.pushStream(inner, next, watching));
            }
        }

        @Override
        public void accept(Object x) { open(mapper.apply(x)); }

        @Override
        public void accept(int x) { open(mapper.apply(x)); }

        @Override
        public void accept(long x) { open(mapper.apply(x)); }

        @Override
        public void accept(double x) { open(mapper.apply(x)); }
    }
}
