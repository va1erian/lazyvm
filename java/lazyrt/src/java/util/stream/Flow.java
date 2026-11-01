package java.util.stream;

import java.util.Comparator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.Supplier;

/**
 * What the four stream kinds share. A stream object is one stage of a pipeline: it knows the stage before it, the
 * layer it adds, the characteristics the pipeline has at this point and whether it was used. A terminal
 * operation builds the line of relays from the last stage back to the first and pushes the source through it.
 */
abstract class Flow<T, S extends BaseStream<T, S>> implements BaseStream<T, S> {
    /** The characteristics a pipeline keeps track of once it has an operation. */
    static final int TRACKED = Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SORTED
            | Spliterator.SIZED | Spliterator.SUBSIZED;
    static final int SIZES = Spliterator.SIZED | Spliterator.SUBSIZED;
    /** A size this large or larger cannot be an array (docs/design/streams.md T6, SP8). */
    static final long MAX_ARRAY = Integer.MAX_VALUE;

    final Pipe pipe;
    /** The stage before this one; null for the first stage, which stands for the source. */
    final Flow<?, ?> prev;
    private final Relay.Layer layer;
    final int flags;
    /** True when the SORTED in `flags` is the natural order (a source without comparator, or sorted()). */
    final boolean natural;
    /** True when this stage or one before it may stop the line early (limit). */
    final boolean stops;
    private boolean used;

    /** The first stage, over the source of `pipe`. */
    Flow(Pipe pipe, int flags, boolean natural) {
        this.pipe = pipe;
        this.prev = null;
        this.layer = null;
        this.flags = flags;
        this.natural = natural;
        this.stops = false;
    }

    /** A later stage: links `from` (failing if it was used) and adds `layer`. */
    Flow(Flow<?, ?> from, Relay.Layer layer, int flags, boolean natural, boolean stops) {
        from.link();
        this.pipe = from.pipe;
        this.prev = from;
        this.layer = layer;
        this.flags = natural ? flags & TRACKED : flags & TRACKED & ~Spliterator.SORTED;
        this.natural = natural;
        this.stops = stops || from.stops;
    }

    /** The next stage of this stream's own kind. */
    abstract S stage(Relay.Layer layer, int flags, boolean natural, boolean stops);

    @SuppressWarnings("unchecked")
    static <X> X cast(Object o) { return (X) o; }

    @SuppressWarnings("unchecked")
    final S self() { return (S) this; }

    /** Marks this stage used, failing if it already was. */
    final void link() {
        if (used) {
            throw new IllegalStateException("stream has already been operated upon or closed");
        }
        used = true;
    }

    final int keep(int drop) { return flags & ~drop; }

    /** The estimate the pipeline starts from. */
    long size() { return pipe.source().estimateSize(); }

    Comparator<?> comparator() {
        if ((flags & Spliterator.SORTED) == 0) {
            throw new IllegalStateException();
        }
        return natural ? null : pipe.source().getComparator();
    }

    // ---- BaseStream ----

    @Override
    public final boolean isParallel() { return pipe.parallel; }

    @Override
    public S sequential() {
        pipe.parallel = false;
        return self();
    }

    @Override
    public S parallel() {
        pipe.parallel = true;
        return self();
    }

    @Override
    public S unordered() {
        if ((flags & Spliterator.ORDERED) == 0) {
            return self();
        }
        return stage(Relay.SAME, keep(Spliterator.ORDERED), natural, false);
    }

    @Override
    public S onClose(Runnable closeHandler) {
        Objects.requireNonNull(closeHandler);
        pipe.add(closeHandler);
        return self();
    }

    @Override
    public void close() {
        used = true;
        if (prev == null) {
            pipe.dropSource();
        }
        pipe.close();
    }

    // ---- operations whose result is the stream's own kind ----

    public S limit(long maxSize) {
        if (maxSize < 0) {
            throw new IllegalArgumentException(Long.toString(maxSize));
        }
        return stage(next -> new Stages.Limit(next, maxSize), keep(SIZES), natural, true);
    }

    public S skip(long n) {
        if (n < 0) {
            throw new IllegalArgumentException(Long.toString(n));
        }
        if (n == 0) {
            return self();
        }
        return stage(new Stages.Skipping(n), keep(SIZES), natural, false);
    }

    /** The layer that gathers and sorts this kind's elements. */
    abstract Relay.Layer sorting();

    public S sorted() {
        Relay.Layer l = natural && (flags & Spliterator.SORTED) != 0 ? Relay.SAME : sorting();
        return stage(l, flags | Spliterator.ORDERED | Spliterator.SORTED, true, false);
    }

    // ---- running ----

    /** The line of relays for the stages up to this one, ending at `end`. */
    final Relay build(Relay end, boolean watch) {
        Relay r = end;
        for (Flow<?, ?> f = this; f.prev != null; f = f.prev) {
            boolean first = f.prev.prev == null;
            r = first && f.layer instanceof Stages.Skipping ? ((Stages.Skipping) f.layer).jump(r, pipe)
                    : f.layer.before(r);
            r.watching = watch;
        }
        return r;
    }

    /** The number of elements the line is told to expect: known only while every stage keeps the count. */
    final long expected(Spliterator<?> sp) {
        return (flags & Spliterator.SIZED) != 0 ? sp.getExactSizeIfKnown() : -1;
    }

    /** The size to tell a terminal that allocates before the run; the stage must already be linked. */
    final long announce() { return expected(pipe.source()); }

    /**
     * Runs the pipeline into `end`. `stops` says the end of the line may want to stop early (find, match);
     * a limit anywhere in the pipeline has the same effect.
     */
    final void run(Relay end, boolean stops) {
        link();
        exec(end, stops);
    }

    /** run() for a stage that was already linked, for terminal operations that check their argument after that. */
    final void exec(Relay end, boolean stops) {
        Spliterator<?> sp = pipe.source();
        boolean watch = stops || this.stops;
        Relay head = build(end, watch);
        head.start(sp.getExactSizeIfKnown());
        Pipe.push(pipe.kind, sp, head, watch);
        head.finish();
    }

    /**
     * Pushes the elements of any stream into `down`, used by flatMap; the stream is made sequential first. When the
     * line may stop early the stream is read through its own spliterator, one step at a time, so that it is not
     * asked for more than the end of the line wants.
     */
    static void pushStream(BaseStream<?, ?> s, Relay down, boolean watch) {
        s.sequential();
        if (!watch && s instanceof Flow) {
            ((Flow<?, ?>) s).run(new Relay.Shield(down), false);
        } else {
            Pipe.push(kindOf(s), s.spliterator(), down, watch);
        }
    }

    private static int kindOf(BaseStream<?, ?> s) {
        return s instanceof IntStream ? Pipe.INTS : s instanceof LongStream ? Pipe.LONGS
                : s instanceof DoubleStream ? Pipe.DOUBLES : Pipe.REFS;
    }

    /** The stepping spliterator behind iterator() and spliterator() of a stage that has operations. */
    abstract Spliterator<?> stepper();

    /** spliterator() of any kind: the source itself for the first stage, else a stepping one. */
    final Spliterator<?> spliteratorOf() {
        if (prev == null) {
            return sourceSplit();
        }
        link();
        pipe.checkOpen();
        return stepper();
    }

    /** The spliterator of the first stage: the source, or a stand-in that asks the supplier on first use. */
    final Spliterator<?> sourceSplit() {
        link();
        if (pipe.isBound()) {
            return pipe.source();
        }
        pipe.checkOpen();
        Supplier<? extends Spliterator<?>> m = pipe.takeMaker();
        return Lazy.of(pipe.kind, m);
    }
}
