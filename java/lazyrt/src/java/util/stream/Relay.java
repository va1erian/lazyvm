package java.util.stream;

import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/**
 * One station of a running pipeline: elements are pushed into it and it pushes its results to the next
 * station. A station takes the kind it is built for (a station of an IntStream implements accept(int)); the
 * others fail, which only a bug in a stage could reach. `start` announces how many elements will come (-1 if
 * unknown), `finish` that no more come, and `halted` that the end of the line wants no more.
 */
abstract class Relay implements Consumer<Object>, IntConsumer, LongConsumer, DoubleConsumer {
    /** Set while the run steps the source and asks `halted` between steps; flatMap and sorted adapt to it. */
    boolean watching;

    void start(long size) {
    }

    void finish() {
    }

    boolean halted() { return false; }

    @Override
    public void accept(Object x) { throw new IllegalStateException(); }

    @Override
    public void accept(int x) { throw new IllegalStateException(); }

    @Override
    public void accept(long x) { throw new IllegalStateException(); }

    @Override
    public void accept(double x) { throw new IllegalStateException(); }

    /** Builds the station that sits in front of `next`. */
    interface Layer {
        Relay before(Relay next);
    }

    /** A layer that does nothing, for a stage that only changes the stream's bookkeeping. */
    static final Layer SAME = next -> next;

    /** A station that passes its announcements on and halts when the next one does. */
    abstract static class Pass extends Relay {
        final Relay next;

        Pass(Relay next) { this.next = next; }

        @Override
        void start(long size) { next.start(size); }

        @Override
        void finish() { next.finish(); }

        @Override
        boolean halted() { return next.halted(); }
    }

    /** A station that can change how many elements come out: whatever size was announced, it announces none. */
    abstract static class Narrow extends Pass {
        Narrow(Relay next) { super(next); }

        @Override
        void start(long size) { next.start(-1); }
    }

    /** Keeps the inner stream of a flatMap from announcing a start or an end to the outer line. */
    static final class Shield extends Pass {
        Shield(Relay next) { super(next); }

        @Override
        void start(long size) {
        }

        @Override
        void finish() {
        }

        @Override
        public void accept(Object x) { next.accept(x); }

        @Override
        public void accept(int x) { next.accept(x); }

        @Override
        public void accept(long x) { next.accept(x); }

        @Override
        public void accept(double x) { next.accept(x); }
    }
}
