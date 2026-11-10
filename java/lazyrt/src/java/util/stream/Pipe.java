package java.util.stream;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * What every stage of one pipeline shares: its source, the parallel flag and the close handlers. The source is a
 * spliterator or a supplier of one that is asked when the pipeline first runs; closing the first stage drops it.
 */
final class Pipe {
    static final int REFS = 0;
    static final int INTS = 1;
    static final int LONGS = 2;
    static final int DOUBLES = 3;

    /** Which element type the source and the stages speak. */
    final int kind;
    boolean parallel;
    private Spliterator<?> source;
    private Supplier<? extends Spliterator<?>> maker;
    private ArrayList<Runnable> handlers;

    Pipe(int kind, Spliterator<?> source, Supplier<? extends Spliterator<?>> maker, boolean parallel) {
        this.kind = kind;
        this.source = source;
        this.maker = maker;
        this.parallel = parallel;
    }

    boolean isBound() { return source != null; }

    /** The source spliterator, asking the supplier the first time. */
    Spliterator<?> source() {
        if (source == null) {
            checkOpen();
            source = Objects.requireNonNull(maker.get());
            maker = null;
        }
        return source;
    }

    void checkOpen() {
        if (source == null && maker == null) {
            throw new IllegalStateException("source already consumed or closed");
        }
    }

    /** What the supplier will make, handed to a spliterator() call that must not ask for it yet. */
    Supplier<? extends Spliterator<?>> takeMaker() {
        Supplier<? extends Spliterator<?>> m = maker;
        maker = null;
        return m;
    }

    void dropSource() {
        source = null;
        maker = null;
    }

    // ---- moving elements from the source into a station ----

    /** Pushes every remaining element of `sp`, a spliterator of this kind, in bulk. */
    @SuppressWarnings("unchecked")
    static void pushAll(int kind, Spliterator<?> sp, Relay to) {
        switch (kind) {
            case INTS: ((Spliterator.OfInt) sp).forEachRemaining((IntConsumer) to); break;
            case LONGS: ((Spliterator.OfLong) sp).forEachRemaining((LongConsumer) to); break;
            case DOUBLES: ((Spliterator.OfDouble) sp).forEachRemaining((DoubleConsumer) to); break;
            default: ((Spliterator<Object>) sp).forEachRemaining((Consumer<Object>) to);
        }
    }

    /** Pushes one element of `sp`; false when it has none left. */
    @SuppressWarnings("unchecked")
    static boolean pushOne(int kind, Spliterator<?> sp, Relay to) {
        switch (kind) {
            case INTS: return ((Spliterator.OfInt) sp).tryAdvance((IntConsumer) to);
            case LONGS: return ((Spliterator.OfLong) sp).tryAdvance((LongConsumer) to);
            case DOUBLES: return ((Spliterator.OfDouble) sp).tryAdvance((DoubleConsumer) to);
            default: return ((Spliterator<Object>) sp).tryAdvance((Consumer<Object>) to);
        }
    }

    /** Pushes everything, one element at a time while the station has not halted, or in bulk. */
    static void push(int kind, Spliterator<?> sp, Relay to, boolean watch) {
        if (!watch) {
            pushAll(kind, sp, to);
            return;
        }
        while (!to.halted() && pushOne(kind, sp, to)) {
            continue;
        }
    }

    // ---- close handlers ----

    void add(Runnable handler) {
        if (handlers == null) {
            handlers = new ArrayList<>();
        }
        handlers.add(handler);
    }

    /** Runs the handlers once. If some fail, the first failure is thrown with the others added to it. */
    void close() {
        ArrayList<Runnable> hs = handlers;
        handlers = null;
        if (hs == null) {
            return;
        }
        Throwable first = null;
        for (Runnable r : hs) {
            try {
                r.run();
            } catch (Throwable t) {
                first = Failures.add(first, t);
            }
        }
        if (first != null) {
            throw Failures.<RuntimeException>raise(first);
        }
    }
}
