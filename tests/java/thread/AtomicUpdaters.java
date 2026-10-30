package thread;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/** The field updaters working: every method, subclass receivers, user subclasses of the updaters. */
public class AtomicUpdaters {
    static void p(Object o) {
        System.out.println(o);
    }

    static class Holder {
        volatile int i;
        volatile long l;
        volatile String s;
        volatile Object o;
        volatile int[] arr;
    }

    static class SubHolder extends Holder {
    }

    static void ints() {
        AtomicIntegerFieldUpdater<Holder> u = AtomicIntegerFieldUpdater.newUpdater(Holder.class, "i");
        Holder h = new SubHolder();
        p(u.get(h) + " " + u.getAndIncrement(h) + " " + u.incrementAndGet(h) + " " + u.getAndDecrement(h) + " "
                + u.decrementAndGet(h));
        p(u.getAndAdd(h, 10) + " " + u.addAndGet(h, -3) + " " + h.i);
        p(u.compareAndSet(h, 6, 1) + " " + u.compareAndSet(h, 7, 1) + " " + h.i);
        p(u.weakCompareAndSet(h, 0, 2) + " " + u.weakCompareAndSet(h, 1, 2) + " " + h.i);
        p(u.getAndSet(h, 9) + " " + h.i);
        u.set(h, Integer.MAX_VALUE);
        p(u.incrementAndGet(h));
        u.lazySet(h, 4);
        p(u.getAndUpdate(h, x -> x * 3) + " " + u.updateAndGet(h, x -> x + 1) + " " + h.i);
        p(u.getAndAccumulate(h, 2, (a, b) -> a - b) + " " + u.accumulateAndGet(h, 5, (a, b) -> a * 100 + b) + " " + h.i);
    }

    static void longs() {
        AtomicLongFieldUpdater<Holder> u = AtomicLongFieldUpdater.newUpdater(Holder.class, "l");
        Holder h = new SubHolder();
        p(u.get(h) + " " + u.getAndIncrement(h) + " " + u.incrementAndGet(h) + " " + u.getAndDecrement(h) + " "
                + u.decrementAndGet(h));
        p(u.getAndAdd(h, 1L << 40) + " " + u.addAndGet(h, -3) + " " + h.l);
        p(u.compareAndSet(h, 6, 1) + " " + u.compareAndSet(h, (1L << 40) - 3, 1) + " " + h.l);
        p(u.weakCompareAndSet(h, 0, 2) + " " + u.weakCompareAndSet(h, 1, 2) + " " + h.l);
        p(u.getAndSet(h, 9) + " " + h.l);
        u.set(h, Long.MAX_VALUE);
        p(u.incrementAndGet(h));
        u.lazySet(h, 4);
        p(u.getAndUpdate(h, x -> x * 3) + " " + u.updateAndGet(h, x -> x + 1) + " " + h.l);
        p(u.getAndAccumulate(h, 2, (a, b) -> a - b) + " " + u.accumulateAndGet(h, 5, (a, b) -> a * 100 + b) + " " + h.l);
    }

    static void references() {
        AtomicReferenceFieldUpdater<Holder, String> u =
                AtomicReferenceFieldUpdater.newUpdater(Holder.class, String.class, "s");
        Holder h = new SubHolder();
        String a = new String("a");
        p(u.get(h) + " " + u.compareAndSet(h, a, a) + " " + u.compareAndSet(h, null, a) + " " + h.s);
        p(u.compareAndSet(h, new String("a"), "b") + " " + u.compareAndSet(h, a, "b") + " " + h.s);
        p(u.weakCompareAndSet(h, "z", "c") + " " + u.weakCompareAndSet(h, "b", "c") + " " + h.s);
        p(u.getAndSet(h, null) + " " + h.s);
        u.set(h, "d");
        u.lazySet(h, "e");
        p(u.getAndUpdate(h, s -> s + "f") + " " + u.updateAndGet(h, s -> s + "g") + " " + h.s);
        p(u.getAndAccumulate(h, "h", (s, t) -> t + s) + " " + u.accumulateAndGet(h, "i", (s, t) -> s + t) + " " + h.s);
        AtomicReferenceFieldUpdater<Holder, Object> o =
                AtomicReferenceFieldUpdater.newUpdater(Holder.class, Object.class, "o");
        o.set(h, 5);
        p(o.getAndSet(h, "x") + " " + o.get(h));
        AtomicReferenceFieldUpdater<Holder, int[]> arr =
                AtomicReferenceFieldUpdater.newUpdater(Holder.class, int[].class, "arr");
        int[] array = {1};
        p(arr.compareAndSet(h, null, array) + " " + (arr.get(h) == array));
    }

    /** A user subclass: which abstract methods the concrete ones are built on. */
    static class Logged extends AtomicIntegerFieldUpdater<Holder> {
        int value = 1;

        @Override
        public boolean compareAndSet(Holder obj, int expect, int update) {
            p("compareAndSet " + expect + " " + update);
            if (value != expect) {
                return false;
            }
            value = update;
            return true;
        }

        @Override
        public boolean weakCompareAndSet(Holder obj, int expect, int update) {
            p("weakCompareAndSet " + expect + " " + update);
            return compareAndSet(obj, expect, update);
        }

        @Override
        public void set(Holder obj, int newValue) {
            p("set " + newValue);
            value = newValue;
        }

        @Override
        public void lazySet(Holder obj, int newValue) {
            p("lazySet " + newValue);
            value = newValue;
        }

        @Override
        public int get(Holder obj) {
            p("get");
            return value;
        }
    }

    static class LoggedLong extends AtomicLongFieldUpdater<Holder> {
        long value = 1;

        @Override
        public boolean compareAndSet(Holder obj, long expect, long update) {
            p("compareAndSet " + expect + " " + update);
            value = update;
            return true;
        }

        @Override
        public boolean weakCompareAndSet(Holder obj, long expect, long update) {
            p("weakCompareAndSet " + expect + " " + update);
            value = update;
            return true;
        }

        @Override
        public void set(Holder obj, long newValue) {
            p("set " + newValue);
        }

        @Override
        public void lazySet(Holder obj, long newValue) {
            p("lazySet " + newValue);
        }

        @Override
        public long get(Holder obj) {
            p("get");
            return value;
        }
    }

    static class LoggedRef extends AtomicReferenceFieldUpdater<Holder, String> {
        String value = "v";

        @Override
        public boolean compareAndSet(Holder obj, String expect, String update) {
            p("compareAndSet " + expect + " " + update);
            value = update;
            return true;
        }

        @Override
        public boolean weakCompareAndSet(Holder obj, String expect, String update) {
            p("weakCompareAndSet " + expect + " " + update);
            value = update;
            return true;
        }

        @Override
        public void set(Holder obj, String newValue) {
            p("set " + newValue);
        }

        @Override
        public void lazySet(Holder obj, String newValue) {
            p("lazySet " + newValue);
        }

        @Override
        public String get(Holder obj) {
            p("get");
            return value;
        }
    }

    /** Do the concrete methods call each other? Each override says when it is reached. */
    static class Layered extends Logged {
        @Override
        public int getAndSet(Holder obj, int newValue) {
            p("[getAndSet]");
            return super.getAndSet(obj, newValue);
        }

        @Override
        public int getAndIncrement(Holder obj) {
            p("[getAndIncrement]");
            return super.getAndIncrement(obj);
        }

        @Override
        public int getAndDecrement(Holder obj) {
            p("[getAndDecrement]");
            return super.getAndDecrement(obj);
        }

        @Override
        public int getAndAdd(Holder obj, int delta) {
            p("[getAndAdd]");
            return super.getAndAdd(obj, delta);
        }

        @Override
        public int incrementAndGet(Holder obj) {
            p("[incrementAndGet]");
            return super.incrementAndGet(obj);
        }

        @Override
        public int decrementAndGet(Holder obj) {
            p("[decrementAndGet]");
            return super.decrementAndGet(obj);
        }

        @Override
        public int addAndGet(Holder obj, int delta) {
            p("[addAndGet]");
            return super.addAndGet(obj, delta);
        }
    }

    static class LayeredLong extends LoggedLong {
        @Override
        public long getAndSet(Holder obj, long newValue) {
            p("[getAndSet]");
            return super.getAndSet(obj, newValue);
        }

        @Override
        public long getAndIncrement(Holder obj) {
            p("[getAndIncrement]");
            return super.getAndIncrement(obj);
        }

        @Override
        public long getAndDecrement(Holder obj) {
            p("[getAndDecrement]");
            return super.getAndDecrement(obj);
        }

        @Override
        public long getAndAdd(Holder obj, long delta) {
            p("[getAndAdd]");
            return super.getAndAdd(obj, delta);
        }

        @Override
        public long incrementAndGet(Holder obj) {
            p("[incrementAndGet]");
            return super.incrementAndGet(obj);
        }

        @Override
        public long decrementAndGet(Holder obj) {
            p("[decrementAndGet]");
            return super.decrementAndGet(obj);
        }

        @Override
        public long addAndGet(Holder obj, long delta) {
            p("[addAndGet]");
            return super.addAndGet(obj, delta);
        }
    }

    static void layers() {
        Holder h = new Holder();
        Layered u = new Layered();
        p("== getAndSet " + u.getAndSet(h, 1));
        p("== getAndIncrement " + u.getAndIncrement(h));
        p("== getAndDecrement " + u.getAndDecrement(h));
        p("== getAndAdd " + u.getAndAdd(h, 2));
        p("== incrementAndGet " + u.incrementAndGet(h));
        p("== decrementAndGet " + u.decrementAndGet(h));
        p("== addAndGet " + u.addAndGet(h, 2));
        LayeredLong l = new LayeredLong();
        p("== long getAndSet " + l.getAndSet(h, 1));
        p("== long getAndIncrement " + l.getAndIncrement(h));
        p("== long getAndDecrement " + l.getAndDecrement(h));
        p("== long getAndAdd " + l.getAndAdd(h, 2));
        p("== long incrementAndGet " + l.incrementAndGet(h));
        p("== long decrementAndGet " + l.decrementAndGet(h));
        p("== long addAndGet " + l.addAndGet(h, 2));
    }

    static void subclasses() {
        Logged u = new Logged();
        Holder h = new Holder();
        p("-- getAndSet");
        p(u.getAndSet(h, 5));
        p("-- getAndIncrement");
        p(u.getAndIncrement(h));
        p("-- getAndDecrement");
        p(u.getAndDecrement(h));
        p("-- getAndAdd");
        p(u.getAndAdd(h, 2));
        p("-- incrementAndGet");
        p(u.incrementAndGet(h));
        p("-- decrementAndGet");
        p(u.decrementAndGet(h));
        p("-- addAndGet");
        p(u.addAndGet(h, 2));
        p("-- getAndUpdate");
        p(u.getAndUpdate(h, x -> x + 1));
        p("-- updateAndGet");
        p(u.updateAndGet(h, x -> x + 1));
        p("-- getAndAccumulate");
        p(u.getAndAccumulate(h, 2, Integer::sum));
        p("-- accumulateAndGet");
        p(u.accumulateAndGet(h, 2, Integer::sum));
        LoggedLong l = new LoggedLong();
        p("-- long getAndSet");
        p(l.getAndSet(h, 5));
        p("-- long getAndIncrement");
        p(l.getAndIncrement(h));
        p("-- long getAndDecrement");
        p(l.getAndDecrement(h));
        p("-- long getAndAdd");
        p(l.getAndAdd(h, 2));
        p("-- long incrementAndGet");
        p(l.incrementAndGet(h));
        p("-- long decrementAndGet");
        p(l.decrementAndGet(h));
        p("-- long addAndGet");
        p(l.addAndGet(h, 2));
        p("-- long updateAndGet");
        p(l.updateAndGet(h, x -> x + 1));
        p("-- long accumulateAndGet");
        p(l.accumulateAndGet(h, 2, Long::sum));
        LoggedRef r = new LoggedRef();
        p("-- ref getAndSet");
        p(r.getAndSet(h, "w"));
        p("-- ref updateAndGet");
        p(r.updateAndGet(h, x -> x + 1));
        p("-- ref getAndAccumulate");
        p(r.getAndAccumulate(h, "y", String::concat));
    }

    public static void main(String[] args) {
        ints();
        longs();
        references();
        subclasses();
        layers();
    }
}
