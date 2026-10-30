package thread;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** The array atomics: values, copies, index errors, toString. */
public class AtomicArrays {
    static void p(Object o) {
        System.out.println(o);
    }

    interface Op {
        void run();
    }

    static void fail(String name, Op op) {
        try {
            op.run();
            p(name + ": no exception");
        } catch (RuntimeException e) {
            p(name + ": " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    static void ints() {
        int[] source = {1, 2, 3};
        AtomicIntegerArray a = new AtomicIntegerArray(source);
        source[0] = 100;
        p(a + " " + a.length() + " " + new AtomicIntegerArray(0) + " " + new AtomicIntegerArray(2));
        p(a.getAndIncrement(0) + " " + a.incrementAndGet(0) + " " + a.getAndDecrement(1) + " " + a.decrementAndGet(1));
        p(a.getAndAdd(2, 10) + " " + a.addAndGet(2, -3) + " " + a);
        p(a.compareAndSet(0, 99, 1) + " " + a.compareAndSet(0, 3, 1) + " " + a);
        p(a.weakCompareAndSet(1, 99, 1) + " " + a.weakCompareAndSet(1, 0, 1) + " " + a);
        p(a.getAndSet(2, 8) + " " + a);
        a.set(2, Integer.MIN_VALUE);
        p(a.decrementAndGet(2));
        a.lazySet(2, 4);
        p(a.getAndUpdate(2, x -> x * 3) + " " + a.updateAndGet(2, x -> x + 1) + " " + a.get(2));
        p(a.getAndAccumulate(2, 2, (x, y) -> x - y) + " " + a.accumulateAndGet(2, 5, (x, y) -> x * 100 + y) + " " + a);
        for (int i : new int[] {-1, 3, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            fail("int get", () -> a.get(i));
            fail("int set", () -> a.set(i, 0));
            fail("int lazySet", () -> a.lazySet(i, 0));
            fail("int getAndSet", () -> a.getAndSet(i, 0));
            fail("int compareAndSet", () -> a.compareAndSet(i, 0, 0));
            fail("int weakCompareAndSet", () -> a.weakCompareAndSet(i, 0, 0));
            fail("int getAndIncrement", () -> a.getAndIncrement(i));
            fail("int addAndGet", () -> a.addAndGet(i, 1));
            fail("int getAndUpdate", () -> a.getAndUpdate(i, x -> x));
            fail("int updateAndGet", () -> a.updateAndGet(i, null));
            fail("int getAndAccumulate", () -> a.getAndAccumulate(i, 1, (x, y) -> x));
            fail("int accumulateAndGet", () -> a.accumulateAndGet(i, 1, null));
        }
        fail("int null", () -> new AtomicIntegerArray(null));
        fail("int negative", () -> new AtomicIntegerArray(-1));
        fail("int null function", () -> a.updateAndGet(0, null));
    }

    static void longs() {
        long[] source = {1, 2, 3};
        AtomicLongArray a = new AtomicLongArray(source);
        source[0] = 100;
        p(a + " " + a.length() + " " + new AtomicLongArray(0) + " " + new AtomicLongArray(2));
        p(a.getAndIncrement(0) + " " + a.incrementAndGet(0) + " " + a.getAndDecrement(1) + " " + a.decrementAndGet(1));
        p(a.getAndAdd(2, 1L << 40) + " " + a.addAndGet(2, -3) + " " + a);
        p(a.compareAndSet(0, 99, 1) + " " + a.compareAndSet(0, 3, 1) + " " + a);
        p(a.weakCompareAndSet(1, 99, 1) + " " + a.weakCompareAndSet(1, 0, 1) + " " + a);
        p(a.getAndSet(2, 8) + " " + a);
        a.set(2, Long.MIN_VALUE);
        p(a.decrementAndGet(2));
        a.lazySet(2, 4);
        p(a.getAndUpdate(2, x -> x * 3) + " " + a.updateAndGet(2, x -> x + 1) + " " + a.get(2));
        p(a.getAndAccumulate(2, 2, (x, y) -> x - y) + " " + a.accumulateAndGet(2, 5, (x, y) -> x * 100 + y) + " " + a);
        for (int i : new int[] {-1, 3}) {
            fail("long get", () -> a.get(i));
            fail("long set", () -> a.set(i, 0));
            fail("long getAndSet", () -> a.getAndSet(i, 0));
            fail("long compareAndSet", () -> a.compareAndSet(i, 0, 0));
            fail("long addAndGet", () -> a.addAndGet(i, 1));
            fail("long updateAndGet", () -> a.updateAndGet(i, x -> x));
            fail("long accumulateAndGet", () -> a.accumulateAndGet(i, 1, (x, y) -> x));
        }
        fail("long null", () -> new AtomicLongArray(null));
        fail("long negative", () -> new AtomicLongArray(-1));
    }

    static void references() {
        String[] source = {"a", "b", null};
        AtomicReferenceArray<String> a = new AtomicReferenceArray<>(source);
        source[0] = "changed";
        p(a + " " + a.length() + " " + new AtomicReferenceArray<>(0) + " " + new AtomicReferenceArray<>(2));
        String kept = a.get(0);
        p(a.compareAndSet(0, new String("a"), "x") + " " + a.compareAndSet(0, kept, "x") + " " + a);
        p(a.weakCompareAndSet(1, "q", "y") + " " + a.weakCompareAndSet(2, null, "y") + " " + a);
        p(a.getAndSet(2, null) + " " + a);
        a.set(2, "s");
        a.lazySet(1, "t");
        p(a.getAndUpdate(2, s -> s + "u") + " " + a.updateAndGet(2, s -> s + "v") + " " + a.get(2));
        p(a.getAndAccumulate(2, "w", (s, t) -> t + s) + " " + a.accumulateAndGet(2, "z", (s, t) -> s + t) + " " + a);
        for (int i : new int[] {-1, 3}) {
            fail("ref get", () -> a.get(i));
            fail("ref set", () -> a.set(i, "s"));
            fail("ref lazySet", () -> a.lazySet(i, "s"));
            fail("ref getAndSet", () -> a.getAndSet(i, "s"));
            fail("ref compareAndSet", () -> a.compareAndSet(i, "s", "s"));
            fail("ref updateAndGet", () -> a.updateAndGet(i, s -> s));
            fail("ref accumulateAndGet", () -> a.accumulateAndGet(i, "s", (s, t) -> s));
        }
        fail("ref null", () -> new AtomicReferenceArray<String>((String[]) null));
        fail("ref negative", () -> new AtomicReferenceArray<String>(-1));
        // The array keeps no trace of the source's element type.
        @SuppressWarnings({"unchecked", "rawtypes"})
        AtomicReferenceArray<Object> raw = (AtomicReferenceArray) new AtomicReferenceArray<>(new String[] {"s"});
        raw.set(0, Integer.valueOf(5));
        p(raw);
        raw.getAndSet(0, Long.valueOf(6));
        p(raw.compareAndSet(0, raw.get(0), Double.valueOf(7)) + " " + raw);
        Object[] nested = {"n"};
        p(new AtomicReferenceArray<Object[]>(new Object[][] {nested}).get(0) == nested);
    }

    public static void main(String[] args) {
        ints();
        longs();
        references();
    }
}
