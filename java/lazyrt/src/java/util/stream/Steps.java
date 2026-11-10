package java.util.stream;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/**
 * Walks a pipeline that has operations one step at a time, for iterator() and spliterator(). A step pushes one
 * element of the source through every later stage and queues whatever comes out of the last one, so a stage
 * that makes several elements of one (flatMap) or none (filter) shows in the queue. A barrier (sorted) swallows
 * the elements until the source is exhausted, and its step releases them all (docs/design/streams.md L11).
 */
final class Steps {
    private final Flow<?, ?> flow;
    private final Queue queue;
    private Relay head;
    private Spliterator<?> source;
    private boolean done;

    /** `kind` is the element type of the stream whose steps these are (Pipe.REFS and so on). */
    Steps(Flow<?, ?> flow, int kind) {
        this.flow = flow;
        this.queue = new Queue(kind);
    }

    /** `watch` is whether stages may stop the line early: always while stepping, as the pipeline says in bulk. */
    private void open(boolean watch) {
        source = flow.pipe.source();
        head = flow.build(queue, watch);
        head.start(source.getExactSizeIfKnown());
    }

    /** Makes sure an element is queued; false when the pipeline has no more. */
    boolean ready() {
        if (head == null) {
            open(true);
        }
        while (queue.isEmpty()) {
            if (done) {
                return false;
            }
            if (head.halted() || !Pipe.pushOne(flow.pipe.kind, source, head)) {
                head.finish();
                done = true;
            }
        }
        return true;
    }

    /** The queued element at the front, boxed. */
    Object take() { return queue.take(); }

    /**
     * Gives `sink` everything that is left. Before the first step the pipeline runs in bulk; once a step has run
     * it goes on stepping, so each later step is still taken whole before its elements are given out.
     */
    void rest(Relay sink) {
        if (head != null) {
            while (ready()) {
                queue.give(sink);
            }
            return;
        }
        open(flow.stops);
        queue.redirect(sink);
        Pipe.push(flow.pipe.kind, source, head, flow.stops);
        head.finish();
        done = true;
    }

    /** The end of the line of a step: holds boxed elements until they are taken, or hands them on directly. */
    private static final class Queue extends Relay {
        private final int kind;
        private Object[] items = new Object[8];
        private int from;
        private int to;
        private Relay direct;

        Queue(int kind) { this.kind = kind; }

        boolean isEmpty() { return from == to; }

        private void put(Object x) {
            if (direct != null) {
                send(direct, x);
                return;
            }
            if (to == items.length) {
                if (from > 0) {
                    System.arraycopy(items, from, items, 0, to - from);
                    to -= from;
                    from = 0;
                } else {
                    items = Arrays.copyOf(items, to * 2);
                }
            }
            items[to++] = x;
        }

        Object take() {
            Object x = items[from];
            items[from++] = null;
            if (from == to) {
                from = 0;
                to = 0;
            }
            return x;
        }

        void give(Relay sink) { send(sink, take()); }

        void redirect(Relay sink) { direct = sink; }

        /** Hands a boxed element to a station in the kind the stream speaks. */
        private void send(Relay sink, Object x) {
            switch (kind) {
                case Pipe.INTS: sink.accept(((Integer) x).intValue()); break;
                case Pipe.LONGS: sink.accept(((Long) x).longValue()); break;
                case Pipe.DOUBLES: sink.accept(((Double) x).doubleValue()); break;
                default: sink.accept(x);
            }
        }

        @Override
        public void accept(Object x) { put(x); }

        @Override
        public void accept(int x) { put(x); }

        @Override
        public void accept(long x) { put(x); }

        @Override
        public void accept(double x) { put(x); }
    }

    // ---- the end of the line of a bulk traversal: the caller's own consumer ----

    private static final class ToRef extends Relay {
        private final Consumer<Object> action;

        ToRef(Consumer<?> action) { this.action = Flow.cast(action); }

        @Override
        public void accept(Object x) { action.accept(x); }
    }

    private static final class ToInt extends Relay {
        private final IntConsumer action;

        ToInt(IntConsumer action) { this.action = action; }

        @Override
        public void accept(int x) { action.accept(x); }
    }

    private static final class ToLong extends Relay {
        private final LongConsumer action;

        ToLong(LongConsumer action) { this.action = action; }

        @Override
        public void accept(long x) { action.accept(x); }
    }

    private static final class ToDouble extends Relay {
        private final DoubleConsumer action;

        ToDouble(DoubleConsumer action) { this.action = action; }

        @Override
        public void accept(double x) { action.accept(x); }
    }

    // ---- the spliterators of the four kinds ----

    abstract static class Base {
        final Steps steps;
        private final Flow<?, ?> flow;

        Base(Flow<?, ?> flow, int kind) {
            this.flow = flow;
            this.steps = new Steps(flow, kind);
        }

        public long estimateSize() { return flow.size(); }

        public int characteristics() { return flow.flags; }

        public Comparator<?> comparatorOf() { return flow.comparator(); }
    }

    static final class Refs<T> extends Base implements Spliterator<T> {
        Refs(Flow<?, ?> flow) { super(flow, Pipe.REFS); }

        @Override
        @SuppressWarnings("unchecked")
        public boolean tryAdvance(Consumer<? super T> action) {
            Objects.requireNonNull(action);
            if (!steps.ready()) {
                return false;
            }
            action.accept((T) steps.take());
            return true;
        }

        @Override
        public void forEachRemaining(Consumer<? super T> action) {
            Objects.requireNonNull(action);
            steps.rest(new ToRef(action));
        }

        @Override
        public Spliterator<T> trySplit() { return null; }

        @Override
        @SuppressWarnings("unchecked")
        public Comparator<? super T> getComparator() { return (Comparator<? super T>) comparatorOf(); }
    }

    static final class Ints extends Base implements Spliterator.OfInt {
        Ints(Flow<?, ?> flow) { super(flow, Pipe.INTS); }

        @Override
        public boolean tryAdvance(IntConsumer action) {
            Objects.requireNonNull(action);
            if (!steps.ready()) {
                return false;
            }
            action.accept((Integer) steps.take());
            return true;
        }

        @Override
        public void forEachRemaining(IntConsumer action) {
            Objects.requireNonNull(action);
            steps.rest(new ToInt(action));
        }

        @Override
        public Spliterator.OfInt trySplit() { return null; }

        @Override
        @SuppressWarnings("unchecked")
        public Comparator<? super Integer> getComparator() { return (Comparator<? super Integer>) comparatorOf(); }
    }

    static final class Longs extends Base implements Spliterator.OfLong {
        Longs(Flow<?, ?> flow) { super(flow, Pipe.LONGS); }

        @Override
        public boolean tryAdvance(LongConsumer action) {
            Objects.requireNonNull(action);
            if (!steps.ready()) {
                return false;
            }
            action.accept((Long) steps.take());
            return true;
        }

        @Override
        public void forEachRemaining(LongConsumer action) {
            Objects.requireNonNull(action);
            steps.rest(new ToLong(action));
        }

        @Override
        public Spliterator.OfLong trySplit() { return null; }

        @Override
        @SuppressWarnings("unchecked")
        public Comparator<? super Long> getComparator() { return (Comparator<? super Long>) comparatorOf(); }
    }

    static final class Doubles extends Base implements Spliterator.OfDouble {
        Doubles(Flow<?, ?> flow) { super(flow, Pipe.DOUBLES); }

        @Override
        public boolean tryAdvance(DoubleConsumer action) {
            Objects.requireNonNull(action);
            if (!steps.ready()) {
                return false;
            }
            action.accept((Double) steps.take());
            return true;
        }

        @Override
        public void forEachRemaining(DoubleConsumer action) {
            Objects.requireNonNull(action);
            steps.rest(new ToDouble(action));
        }

        @Override
        public Spliterator.OfDouble trySplit() { return null; }

        @Override
        @SuppressWarnings("unchecked")
        public Comparator<? super Double> getComparator() {
            return (Comparator<? super Double>) comparatorOf();
        }
    }
}
