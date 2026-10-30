package thread;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicMarkableReference;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/** Which overridable methods the others call: each override logs, and some change the answer. */
public class AtomicOverrides {
    static void log(String call) {
        System.out.println("  [" + call + "]");
    }

    static void show(String name, Object value) {
        System.out.println(name + " = " + value);
    }

    static class MyLongAdder extends LongAdder {
        @Override public void add(long x) { log("add"); super.add(x); }
        @Override public void increment() { log("increment"); super.increment(); }
        @Override public void decrement() { log("decrement"); super.decrement(); }
        @Override public long sum() { log("sum"); return super.sum() + 1000; }
        @Override public void reset() { log("reset"); super.reset(); }
        @Override public long sumThenReset() { log("sumThenReset"); return super.sumThenReset(); }
        @Override public long longValue() { log("longValue"); return super.longValue(); }
        @Override public int intValue() { log("intValue"); return super.intValue(); }
        @Override public float floatValue() { log("floatValue"); return super.floatValue(); }
        @Override public double doubleValue() { log("doubleValue"); return super.doubleValue(); }
    }

    static void longAdder() {
        LongAdder a = new MyLongAdder();
        a.add(1);
        a.increment();
        a.decrement();
        show("toString", a.toString());
        show("sum", a.sum());
        show("longValue", a.longValue());
        show("intValue", a.intValue());
        show("floatValue", a.floatValue());
        show("doubleValue", a.doubleValue());
        show("shortValue", a.shortValue());
        show("byteValue", a.byteValue());
        show("sumThenReset", a.sumThenReset());
        a.reset();
    }

    static class MyDoubleAdder extends DoubleAdder {
        @Override public void add(double x) { log("add"); super.add(x); }
        @Override public double sum() { log("sum"); return super.sum() + 1000; }
        @Override public void reset() { log("reset"); super.reset(); }
        @Override public double sumThenReset() { log("sumThenReset"); return super.sumThenReset(); }
        @Override public long longValue() { log("longValue"); return super.longValue(); }
        @Override public int intValue() { log("intValue"); return super.intValue(); }
        @Override public float floatValue() { log("floatValue"); return super.floatValue(); }
        @Override public double doubleValue() { log("doubleValue"); return super.doubleValue(); }
    }

    static void doubleAdder() {
        DoubleAdder a = new MyDoubleAdder();
        a.add(1);
        show("toString", a.toString());
        show("sum", a.sum());
        show("longValue", a.longValue());
        show("intValue", a.intValue());
        show("floatValue", a.floatValue());
        show("doubleValue", a.doubleValue());
        show("shortValue", a.shortValue());
        show("sumThenReset", a.sumThenReset());
        a.reset();
    }

    static class MyLongAccumulator extends LongAccumulator {
        MyLongAccumulator() {
            super(Long::sum, 0);
        }

        @Override public void accumulate(long x) { log("accumulate"); super.accumulate(x); }
        @Override public long get() { log("get"); return super.get() + 1000; }
        @Override public void reset() { log("reset"); super.reset(); }
        @Override public long getThenReset() { log("getThenReset"); return super.getThenReset(); }
        @Override public long longValue() { log("longValue"); return super.longValue(); }
        @Override public int intValue() { log("intValue"); return super.intValue(); }
        @Override public float floatValue() { log("floatValue"); return super.floatValue(); }
        @Override public double doubleValue() { log("doubleValue"); return super.doubleValue(); }
    }

    static void longAccumulator() {
        LongAccumulator a = new MyLongAccumulator();
        a.accumulate(1);
        show("toString", a.toString());
        show("get", a.get());
        show("longValue", a.longValue());
        show("intValue", a.intValue());
        show("floatValue", a.floatValue());
        show("doubleValue", a.doubleValue());
        show("shortValue", a.shortValue());
        show("getThenReset", a.getThenReset());
        a.reset();
    }

    static class MyDoubleAccumulator extends DoubleAccumulator {
        MyDoubleAccumulator() {
            super(Double::sum, 0);
        }

        @Override public void accumulate(double x) { log("accumulate"); super.accumulate(x); }
        @Override public double get() { log("get"); return super.get() + 1000; }
        @Override public void reset() { log("reset"); super.reset(); }
        @Override public double getThenReset() { log("getThenReset"); return super.getThenReset(); }
        @Override public long longValue() { log("longValue"); return super.longValue(); }
        @Override public int intValue() { log("intValue"); return super.intValue(); }
        @Override public float floatValue() { log("floatValue"); return super.floatValue(); }
        @Override public double doubleValue() { log("doubleValue"); return super.doubleValue(); }
    }

    static void doubleAccumulator() {
        DoubleAccumulator a = new MyDoubleAccumulator();
        a.accumulate(1);
        show("toString", a.toString());
        show("get", a.get());
        show("longValue", a.longValue());
        show("intValue", a.intValue());
        show("floatValue", a.floatValue());
        show("doubleValue", a.doubleValue());
        show("shortValue", a.shortValue());
        show("getThenReset", a.getThenReset());
        a.reset();
    }

    static class MyStamped extends AtomicStampedReference<String> {
        MyStamped() {
            super("a", 1);
        }

        @Override public String getReference() { log("getReference"); return super.getReference(); }
        @Override public int getStamp() { log("getStamp"); return super.getStamp(); }
        @Override public String get(int[] holder) { log("get"); return super.get(holder); }
        @Override public boolean compareAndSet(String e, String n, int es, int ns) {
            log("compareAndSet");
            return super.compareAndSet(e, n, es, ns);
        }
        @Override public boolean weakCompareAndSet(String e, String n, int es, int ns) {
            log("weakCompareAndSet");
            return super.weakCompareAndSet(e, n, es, ns);
        }
        @Override public void set(String n, int ns) { log("set"); super.set(n, ns); }
        @Override public boolean attemptStamp(String e, int ns) { log("attemptStamp"); return super.attemptStamp(e, ns); }
    }

    static void stamped() {
        MyStamped s = new MyStamped();
        show("getReference", s.getReference());
        show("getStamp", s.getStamp());
        show("get", s.get(new int[1]));
        show("compareAndSet", s.compareAndSet("a", "a", 1, 1));
        show("weakCompareAndSet", s.weakCompareAndSet("a", "a", 1, 1));
        s.set("a", 1);
        show("attemptStamp", s.attemptStamp("a", 1));
    }

    static class MyMarkable extends AtomicMarkableReference<String> {
        MyMarkable() {
            super("a", true);
        }

        @Override public String getReference() { log("getReference"); return super.getReference(); }
        @Override public boolean isMarked() { log("isMarked"); return super.isMarked(); }
        @Override public String get(boolean[] holder) { log("get"); return super.get(holder); }
        @Override public boolean compareAndSet(String e, String n, boolean es, boolean ns) {
            log("compareAndSet");
            return super.compareAndSet(e, n, es, ns);
        }
        @Override public boolean weakCompareAndSet(String e, String n, boolean es, boolean ns) {
            log("weakCompareAndSet");
            return super.weakCompareAndSet(e, n, es, ns);
        }
        @Override public void set(String n, boolean ns) { log("set"); super.set(n, ns); }
        @Override public boolean attemptMark(String e, boolean ns) { log("attemptMark"); return super.attemptMark(e, ns); }
    }

    static void markable() {
        MyMarkable m = new MyMarkable();
        show("getReference", m.getReference());
        show("isMarked", m.isMarked());
        show("get", m.get(new boolean[1]));
        show("compareAndSet", m.compareAndSet("a", "a", true, true));
        show("weakCompareAndSet", m.weakCompareAndSet("a", "a", true, true));
        m.set("a", true);
        show("attemptMark", m.attemptMark("a", true));
    }

    static class MyInteger extends AtomicInteger {
        @Override public int intValue() { log("intValue"); return super.intValue(); }
        @Override public long longValue() { log("longValue"); return super.longValue(); }
        @Override public float floatValue() { log("floatValue"); return super.floatValue(); }
        @Override public double doubleValue() { log("doubleValue"); return super.doubleValue(); }
        @Override public String toString() { log("toString"); return super.toString(); }
    }

    static class MyLong extends AtomicLong {
        @Override public int intValue() { log("intValue"); return super.intValue(); }
        @Override public long longValue() { log("longValue"); return super.longValue(); }
        @Override public float floatValue() { log("floatValue"); return super.floatValue(); }
        @Override public double doubleValue() { log("doubleValue"); return super.doubleValue(); }
        @Override public String toString() { log("toString"); return super.toString(); }
    }

    static void conversions(Number n) {
        show("intValue", n.intValue());
        show("longValue", n.longValue());
        show("floatValue", n.floatValue());
        show("doubleValue", n.doubleValue());
        show("shortValue", n.shortValue());
        show("byteValue", n.byteValue());
        show("toString", n.toString());
    }

    static class MyLongArray extends AtomicLongArray {
        MyLongArray() {
            super(1);
        }

        @Override public long addAndGet(int i, long delta) { log("addAndGet"); return super.addAndGet(i, delta) + 1000; }
    }

    static class MyBoolean extends AtomicBoolean {
        @Override public boolean weakCompareAndSet(boolean e, boolean u) { log("weak"); return super.weakCompareAndSet(e, u); }
        @Override public String toString() { log("toString"); return super.toString(); }
    }

    static class MyReference extends AtomicReference<String> {
        @Override public String toString() { log("toString"); return super.toString(); }
    }

    static void others() {
        MyLongArray a = new MyLongArray();
        show("incrementAndGet", a.incrementAndGet(0));
        show("decrementAndGet", a.decrementAndGet(0));
        show("getAndAdd", a.getAndAdd(0, 2));
        show("addAndGet", a.addAndGet(0, 2));
        show("accumulateAndGet", a.accumulateAndGet(0, 2, Long::sum));
        show("toString", a.toString());
        MyBoolean b = new MyBoolean();
        show("compareAndSet", b.compareAndSet(false, true));
        show("weak", b.weakCompareAndSet(true, false));
        show("toString", b.toString());
        show("reference", new MyReference().toString());
    }

    public static void main(String[] args) {
        System.out.println("-- LongAdder");
        longAdder();
        System.out.println("-- DoubleAdder");
        doubleAdder();
        System.out.println("-- LongAccumulator");
        longAccumulator();
        System.out.println("-- DoubleAccumulator");
        doubleAccumulator();
        System.out.println("-- AtomicStampedReference");
        stamped();
        System.out.println("-- AtomicMarkableReference");
        markable();
        System.out.println("-- AtomicInteger");
        conversions(new MyInteger());
        System.out.println("-- AtomicLong");
        conversions(new MyLong());
        System.out.println("-- others");
        others();
    }
}
