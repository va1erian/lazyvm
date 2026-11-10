package java.util.stream;

import java.util.Arrays;
import java.util.function.BinaryOperator;
import java.util.function.DoubleBinaryOperator;
import java.util.function.IntBinaryOperator;
import java.util.function.IntFunction;
import java.util.function.LongBinaryOperator;

/** The stations that end a line: what the terminal operations collect their answer in. */
final class Ends {
    private Ends() {
    }

    /** Counts the elements of any kind. */
    static final class Count extends Relay {
        long n;

        @Override
        public void accept(Object x) { n++; }

        @Override
        public void accept(int x) { n++; }

        @Override
        public void accept(long x) { n++; }

        @Override
        public void accept(double x) { n++; }
    }

    /** Takes the first element and halts the line. */
    static final class First extends Relay {
        boolean found;
        Object value;

        private void set(Object x) {
            if (!found) {
                found = true;
                value = x;
            }
        }

        @Override
        boolean halted() { return found; }

        @Override
        public void accept(Object x) { set(x); }

        @Override
        public void accept(int x) { set(x); }

        @Override
        public void accept(long x) { set(x); }

        @Override
        public void accept(double x) { set(x); }
    }

    /** anyMatch, allMatch and noneMatch: halts when one outcome of the test decides the answer. */
    abstract static class Match extends Relay {
        private final boolean decisive;
        private final boolean ifDecided;
        private boolean decided;

        /** `decisive` is the test outcome that settles it; `ifDecided` the answer then. */
        Match(boolean decisive, boolean ifDecided) {
            this.decisive = decisive;
            this.ifDecided = ifDecided;
        }

        final void see(boolean outcome) {
            if (outcome == decisive) {
                decided = true;
            }
        }

        @Override
        boolean halted() { return decided; }

        final boolean answer() { return decided ? ifDecided : !ifDecided; }
    }

    /** reduce over objects: the first element starts the result unless an identity did. */
    static final class Fold extends Relay {
        private final BinaryOperator<Object> op;
        Object acc;
        boolean any;

        Fold(BinaryOperator<Object> op) { this.op = op; }

        Fold(Object identity, BinaryOperator<Object> op) {
            this.op = op;
            this.acc = identity;
            this.any = true;
        }

        @Override
        public void accept(Object x) {
            acc = any ? op.apply(acc, x) : x;
            any = true;
        }
    }

    static final class FoldInt extends Relay {
        private final IntBinaryOperator op;
        int acc;
        boolean any;

        FoldInt(IntBinaryOperator op) { this.op = op; }

        FoldInt(int identity, IntBinaryOperator op) {
            this.op = op;
            this.acc = identity;
            this.any = true;
        }

        @Override
        public void accept(int x) {
            acc = any ? op.applyAsInt(acc, x) : x;
            any = true;
        }
    }

    static final class FoldLong extends Relay {
        private final LongBinaryOperator op;
        long acc;
        boolean any;

        FoldLong(LongBinaryOperator op) { this.op = op; }

        FoldLong(long identity, LongBinaryOperator op) {
            this.op = op;
            this.acc = identity;
            this.any = true;
        }

        @Override
        public void accept(long x) {
            acc = any ? op.applyAsLong(acc, x) : x;
            any = true;
        }
    }

    static final class FoldDouble extends Relay {
        private final DoubleBinaryOperator op;
        double acc;
        boolean any;

        FoldDouble(DoubleBinaryOperator op) { this.op = op; }

        FoldDouble(double identity, DoubleBinaryOperator op) {
            this.op = op;
            this.acc = identity;
            this.any = true;
        }

        @Override
        public void accept(double x) {
            acc = any ? op.applyAsDouble(acc, x) : x;
            any = true;
        }
    }

    /** The sum of ints (wrapping in an int) and the sum of longs. */
    static final class SumInt extends Relay {
        int total;

        @Override
        public void accept(int x) { total += x; }
    }

    static final class SumLong extends Relay {
        long total;

        @Override
        public void accept(long x) { total += x; }
    }

    /** The count and the sum, in a long, of ints or longs: what average() needs. */
    static final class Mean extends Relay {
        long n;
        long total;

        @Override
        public void accept(int x) {
            n++;
            total += x;
        }

        @Override
        public void accept(long x) {
            n++;
            total += x;
        }
    }

    // ---- arrays ----

    /** The length of the array for an announced `size`; one that cannot be an array runs out of memory. */
    private static int fit(long size) {
        if (size >= Flow.MAX_ARRAY) {
            throw new OutOfMemoryError();
        }
        return (int) size;
    }

    private static IllegalStateException tooMany(int length) {
        return new IllegalStateException("Accept exceeded fixed size of " + length);
    }

    private static IllegalStateException tooFew(int n, int length) {
        return new IllegalStateException("End size " + n + " is less than fixed size " + length);
    }

    /**
     * toArray over objects. With an announced size the array comes from the generator at once and is filled in
     * place; without one the elements are gathered and the generator is asked for the final count.
     */
    static final class Refs<A> extends Relay {
        private final IntFunction<A[]> generator;
        private A[] out;
        private Object[] gathered;
        private int n;

        /** `size` is what the pipeline says it will deliver (-1 if it does not say): the array is made now. */
        Refs(IntFunction<A[]> generator, long size) {
            this.generator = generator;
            if (size >= 0) {
                out = java.util.Objects.requireNonNull(generator.apply(fit(size)));
            } else {
                gathered = new Object[16];
            }
        }

        @Override
        void start(long size) {
            if (out != null && out.length != size) {
                throw new IllegalStateException("Begin size " + size + " is not equal to fixed size " + out.length);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public void accept(Object x) {
            if (gathered != null) {
                if (n == gathered.length) {
                    gathered = Arrays.copyOf(gathered, n * 2);
                }
                gathered[n++] = x;
            } else {
                if (n >= out.length) {
                    throw tooMany(out.length);
                }
                out[n++] = (A) x;
            }
        }

        @Override
        void finish() {
            if (gathered != null) {
                A[] made = generator.apply(n);
                if (made.length < n) {
                    throw new IndexOutOfBoundsException("does not fit");
                }
                System.arraycopy(gathered, 0, made, 0, n);
                out = made;
            } else if (n < out.length) {
                throw tooFew(n, out.length);
            }
        }

        A[] result() { return out; }
    }

    static final class Ints extends Relay {
        private int[] out;
        private int n;
        private final boolean fixed;

        /** `size` is what the pipeline says it will deliver (-1 if it does not say). */
        Ints(long size) {
            fixed = size >= 0;
            out = new int[fixed ? fit(size) : 16];
        }

        @Override
        void start(long size) {
            if (fixed && size != out.length) {
                throw new IllegalStateException("Begin size " + size + " is not equal to fixed size " + out.length);
            }
        }

        @Override
        public void accept(int x) {
            if (n == out.length) {
                if (fixed) {
                    throw tooMany(n);
                }
                out = Arrays.copyOf(out, n * 2);
            }
            out[n++] = x;
        }

        @Override
        void finish() {
            if (fixed && n < out.length) {
                throw tooFew(n, out.length);
            }
        }

        int[] result() { return n == out.length ? out : Arrays.copyOf(out, n); }
    }

    static final class Longs extends Relay {
        private long[] out;
        private int n;
        private final boolean fixed;

        /** `size` is what the pipeline says it will deliver (-1 if it does not say). */
        Longs(long size) {
            fixed = size >= 0;
            out = new long[fixed ? fit(size) : 16];
        }

        @Override
        void start(long size) {
            if (fixed && size != out.length) {
                throw new IllegalStateException("Begin size " + size + " is not equal to fixed size " + out.length);
            }
        }

        @Override
        public void accept(long x) {
            if (n == out.length) {
                if (fixed) {
                    throw tooMany(n);
                }
                out = Arrays.copyOf(out, n * 2);
            }
            out[n++] = x;
        }

        @Override
        void finish() {
            if (fixed && n < out.length) {
                throw tooFew(n, out.length);
            }
        }

        long[] result() { return n == out.length ? out : Arrays.copyOf(out, n); }
    }

    static final class Doubles extends Relay {
        private double[] out;
        private int n;
        private final boolean fixed;

        /** `size` is what the pipeline says it will deliver (-1 if it does not say). */
        Doubles(long size) {
            fixed = size >= 0;
            out = new double[fixed ? fit(size) : 16];
        }

        @Override
        void start(long size) {
            if (fixed && size != out.length) {
                throw new IllegalStateException("Begin size " + size + " is not equal to fixed size " + out.length);
            }
        }

        @Override
        public void accept(double x) {
            if (n == out.length) {
                if (fixed) {
                    throw tooMany(n);
                }
                out = Arrays.copyOf(out, n * 2);
            }
            out[n++] = x;
        }

        @Override
        void finish() {
            if (fixed && n < out.length) {
                throw tooFew(n, out.length);
            }
        }

        double[] result() { return n == out.length ? out : Arrays.copyOf(out, n); }
    }
}
