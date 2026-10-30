package thread;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicMarkableReference;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/** S6-03's check: many threads hammer every kind of atomic; totals must not lose an update. */
public class AtomicCounters {
    static final int THREADS = 8;
    static final int ROUNDS = 30000;

    volatile int intField;
    volatile long longField;
    volatile Integer refField = 0;

    static final AtomicIntegerFieldUpdater<AtomicCounters> INT_FIELD =
            AtomicIntegerFieldUpdater.newUpdater(AtomicCounters.class, "intField");
    static final AtomicLongFieldUpdater<AtomicCounters> LONG_FIELD =
            AtomicLongFieldUpdater.newUpdater(AtomicCounters.class, "longField");
    static final AtomicReferenceFieldUpdater<AtomicCounters, Integer> REF_FIELD =
            AtomicReferenceFieldUpdater.newUpdater(AtomicCounters.class, Integer.class, "refField");

    final AtomicInteger atomicInt = new AtomicInteger();
    final AtomicLong atomicLong = new AtomicLong();
    final AtomicInteger casLoop = new AtomicInteger();
    final AtomicInteger updated = new AtomicInteger();
    final AtomicLong accumulated = new AtomicLong();
    final AtomicReference<Integer> atomicRef = new AtomicReference<>(0);
    final AtomicIntegerArray intArray = new AtomicIntegerArray(4);
    final AtomicLongArray longArray = new AtomicLongArray(4);
    final AtomicReferenceArray<Integer> refArray = new AtomicReferenceArray<>(new Integer[] {0, 0});
    final AtomicStampedReference<Integer> stamped = new AtomicStampedReference<>(0, 0);
    final AtomicMarkableReference<Integer> markable = new AtomicMarkableReference<>(0, false);
    final AtomicBoolean spin = new AtomicBoolean();
    int guarded;
    final AtomicLong swapLong = new AtomicLong();
    final AtomicLong swapLongOut = new AtomicLong();
    final AtomicIntegerArray swapInt = new AtomicIntegerArray(1);
    final AtomicLong swapIntOut = new AtomicLong();
    final AtomicReference<Long> swapRef = new AtomicReference<>(0L);
    final AtomicLong swapRefOut = new AtomicLong();
    final LongAdder longAdder = new LongAdder();
    final DoubleAdder doubleAdder = new DoubleAdder();
    final LongAccumulator maximum = new LongAccumulator(Long::max, Long.MIN_VALUE);
    final DoubleAccumulator doubleSum = new DoubleAccumulator(Double::sum, 0.0);

    void addRef() {
        Integer current;
        do {
            current = atomicRef.get();
        } while (!atomicRef.compareAndSet(current, new Integer(current + 1)));
    }

    void addStamped() {
        int[] holder = new int[1];
        Integer current;
        do {
            current = stamped.get(holder);
        } while (!stamped.compareAndSet(current, new Integer(current + 1), holder[0], holder[0] + 2));
    }

    void addMarkable() {
        boolean[] holder = new boolean[1];
        Integer current;
        do {
            current = markable.get(holder);
        } while (!markable.compareAndSet(current, new Integer(current + 1), holder[0], !holder[0]));
    }

    void addRefArray(int i) {
        Integer current;
        do {
            current = refArray.get(i);
        } while (!refArray.compareAndSet(i, current, new Integer(current + 1)));
    }

    void addRefField() {
        Integer current;
        do {
            current = REF_FIELD.get(this);
        } while (!REF_FIELD.compareAndSet(this, current, new Integer(current + 1)));
    }

    /** A lock made of an AtomicBoolean: only a correct getAndSet keeps `guarded` exact. */
    void addGuarded() {
        while (spin.getAndSet(true)) {
            Thread.yield();
        }
        guarded = guarded + 1;
        spin.set(false);
    }

    /** Every token put in comes out once: a non-atomic swap loses one and repeats another. */
    void swap(int token) {
        swapLongOut.addAndGet(swapLong.getAndSet(token));
        swapIntOut.addAndGet(swapInt.getAndSet(0, token));
        swapRefOut.addAndGet(swapRef.getAndSet(Long.valueOf(token)));
    }

    void round(int id, int i) {
        atomicInt.incrementAndGet();
        atomicLong.getAndAdd(3);
        int v;
        do {
            v = casLoop.get();
        } while (!casLoop.compareAndSet(v, v + 2));
        updated.updateAndGet(x -> x + 1);
        accumulated.accumulateAndGet(5, Long::sum);
        addRef();
        intArray.incrementAndGet(i & 3);
        longArray.addAndGet(i & 3, 2);
        addRefArray(i & 1);
        addStamped();
        addMarkable();
        addGuarded();
        INT_FIELD.getAndIncrement(this);
        LONG_FIELD.addAndGet(this, 7);
        addRefField();
        longAdder.increment();
        doubleAdder.add(0.5);
        maximum.accumulate(id * 1000000L + i);
        doubleSum.accumulate(0.25);
        swap(id * ROUNDS + i + 1);
    }

    void report() {
        long total = (long) THREADS * ROUNDS;
        System.out.println("expected rounds " + total);
        System.out.println("atomicInt " + atomicInt);
        System.out.println("atomicLong " + atomicLong);
        System.out.println("casLoop " + casLoop);
        System.out.println("updated " + updated);
        System.out.println("accumulated " + accumulated);
        System.out.println("atomicRef " + atomicRef);
        System.out.println("intArray " + intArray);
        System.out.println("longArray " + longArray);
        System.out.println("refArray " + refArray);
        int[] holder = new int[1];
        System.out.println("stamped " + stamped.get(holder) + " stamp " + holder[0]);
        boolean[] mark = new boolean[1];
        System.out.println("markable " + markable.get(mark) + " mark " + mark[0]);
        System.out.println("guarded " + guarded);
        System.out.println("intField " + intField);
        System.out.println("longField " + longField);
        System.out.println("refField " + refField);
        System.out.println("longAdder " + longAdder);
        System.out.println("doubleAdder " + doubleAdder);
        System.out.println("maximum " + maximum);
        System.out.println("doubleSum " + doubleSum);
        System.out.println("swapLong " + (swapLongOut.get() + swapLong.get()));
        System.out.println("swapInt " + (swapIntOut.get() + swapInt.get(0)));
        System.out.println("swapRef " + (swapRefOut.get() + swapRef.get()));
    }

    public static void main(String[] args) throws InterruptedException {
        AtomicCounters counters = new AtomicCounters();
        Thread[] threads = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            int id = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < ROUNDS; i++) {
                    counters.round(id, i);
                }
            });
            threads[t].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        counters.report();
    }
}
