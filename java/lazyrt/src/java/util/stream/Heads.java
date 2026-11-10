package java.util.stream;

import java.util.Spliterator;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Where streams start: a spliterator, a supplier of one, iterate, generate and concat. */
final class Heads {
    private Heads() {
    }

    /** SORTED counts for sorted() and distinct() only when the order is the natural one. */
    private static boolean natural(Spliterator<?> sp) {
        return (sp.characteristics() & Spliterator.SORTED) != 0 && sp.getComparator() == null;
    }

    static <T> Stream<T> ofRefs(Spliterator<T> sp, boolean parallel) {
        return new RefFlow<T>(new Pipe(Pipe.REFS, sp, null, parallel), sp.characteristics(), natural(sp));
    }

    static <T> Stream<T> ofRefs(Supplier<? extends Spliterator<T>> sup, int ch, boolean parallel) {
        return new RefFlow<T>(new Pipe(Pipe.REFS, null, sup, parallel), ch, (ch & Spliterator.SORTED) != 0);
    }

    static IntStream ofInts(Spliterator.OfInt sp, boolean parallel) {
        return new IntFlow(new Pipe(Pipe.INTS, sp, null, parallel), sp.characteristics(), natural(sp));
    }

    static IntStream ofInts(Supplier<? extends Spliterator.OfInt> sup, int ch, boolean parallel) {
        return new IntFlow(new Pipe(Pipe.INTS, null, sup, parallel), ch, (ch & Spliterator.SORTED) != 0);
    }

    static LongStream ofLongs(Spliterator.OfLong sp, boolean parallel) {
        return new LongFlow(new Pipe(Pipe.LONGS, sp, null, parallel), sp.characteristics(), natural(sp));
    }

    static LongStream ofLongs(Supplier<? extends Spliterator.OfLong> sup, int ch, boolean parallel) {
        return new LongFlow(new Pipe(Pipe.LONGS, null, sup, parallel), ch, (ch & Spliterator.SORTED) != 0);
    }

    static DoubleStream ofDoubles(Spliterator.OfDouble sp, boolean parallel) {
        return new DoubleFlow(new Pipe(Pipe.DOUBLES, sp, null, parallel), sp.characteristics(), natural(sp));
    }

    static DoubleStream ofDoubles(Supplier<? extends Spliterator.OfDouble> sup, int ch, boolean parallel) {
        return new DoubleFlow(new Pipe(Pipe.DOUBLES, null, sup, parallel), ch, (ch & Spliterator.SORTED) != 0);
    }

    static <T> Stream<T> iterate(T seed, UnaryOperator<T> f) {
        return ofRefs(new Sources.Iterate<T>(seed, f), false);
    }

    static <T> Stream<T> generate(Supplier<T> s) { return ofRefs(new Sources.Generate<T>(s), false); }

    static IntStream iterateInt(int seed, IntUnaryOperator f) { return ofInts(new Sources.IterateInt(seed, f), false); }

    static IntStream generateInt(IntSupplier s) { return ofInts(new Sources.GenerateInt(s), false); }

    static LongStream iterateLong(long seed, LongUnaryOperator f) {
        return ofLongs(new Sources.IterateLong(seed, f), false);
    }

    static LongStream generateLong(LongSupplier s) { return ofLongs(new Sources.GenerateLong(s), false); }

    static DoubleStream iterateDouble(double seed, DoubleUnaryOperator f) {
        return ofDoubles(new Sources.IterateDouble(seed, f), false);
    }

    static DoubleStream generateDouble(DoubleSupplier s) { return ofDoubles(new Sources.GenerateDouble(s), false); }

    // ---- concat: the inputs' spliterators are joined; closing the result closes both inputs first ----

    private static Pipe joined(int kind, Spliterator<?> both, BaseStream<?, ?> a, BaseStream<?, ?> b) {
        Pipe p = new Pipe(kind, both, null, a.isParallel() || b.isParallel());
        p.add(a::close);
        p.add(b::close);
        return p;
    }

    static <T> Stream<T> concat(Stream<? extends T> a, Stream<? extends T> b) {
        Spliterator<T> first = Flow.cast(a.spliterator());
        Sources.JoinRefs<T> both = new Sources.JoinRefs<T>(first, Flow.<Spliterator<T>>cast(b.spliterator()));
        return new RefFlow<T>(joined(Pipe.REFS, both, a, b), both.characteristics(), false);
    }

    static IntStream concatInt(IntStream a, IntStream b) {
        Sources.JoinInts both = new Sources.JoinInts(a.spliterator(), b.spliterator());
        return new IntFlow(joined(Pipe.INTS, both, a, b), both.characteristics(), false);
    }

    static LongStream concatLong(LongStream a, LongStream b) {
        Sources.JoinLongs both = new Sources.JoinLongs(a.spliterator(), b.spliterator());
        return new LongFlow(joined(Pipe.LONGS, both, a, b), both.characteristics(), false);
    }

    static DoubleStream concatDouble(DoubleStream a, DoubleStream b) {
        Sources.JoinDoubles both = new Sources.JoinDoubles(a.spliterator(), b.spliterator());
        return new DoubleFlow(joined(Pipe.DOUBLES, both, a, b), both.characteristics(), false);
    }
}
