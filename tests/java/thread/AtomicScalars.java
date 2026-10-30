package thread;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** One-thread semantics of AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference. */
public class AtomicScalars {
    static void p(Object o) {
        System.out.println(o);
    }

    static void booleans() {
        AtomicBoolean b = new AtomicBoolean();
        p(b + " " + b.get() + " " + new AtomicBoolean(true));
        p(b.compareAndSet(true, false) + " " + b);
        p(b.compareAndSet(false, true) + " " + b);
        p(b.weakCompareAndSet(false, false) + " " + b);
        p(b.weakCompareAndSet(true, false) + " " + b);
        p(b.getAndSet(true) + " " + b.getAndSet(true) + " " + b);
        b.set(false);
        p(b);
        b.lazySet(true);
        p(b);
        p(b.equals(new AtomicBoolean(true)) + " " + b.equals(b));
    }

    static void ints() {
        AtomicInteger i = new AtomicInteger();
        p(i + " " + new AtomicInteger(-5));
        p(i.getAndIncrement() + " " + i.incrementAndGet() + " " + i.getAndDecrement() + " " + i.decrementAndGet());
        p(i.getAndAdd(10) + " " + i.addAndGet(-3) + " " + i.get());
        p(i.compareAndSet(6, 1) + " " + i.compareAndSet(7, 1) + " " + i);
        p(i.weakCompareAndSet(0, 2) + " " + i.weakCompareAndSet(1, 2) + " " + i);
        p(i.getAndSet(9) + " " + i);
        i.set(Integer.MAX_VALUE);
        p(i.incrementAndGet() + " " + i.getAndDecrement() + " " + i);
        i.lazySet(4);
        p(i.getAndUpdate(x -> x * 3) + " " + i.updateAndGet(x -> x + 1) + " " + i);
        p(i.getAndAccumulate(2, (a, b) -> a - b) + " " + i.accumulateAndGet(5, (a, b) -> a * 100 + b) + " " + i);
        i.set(-1234567);
        p(i.intValue() + " " + i.longValue() + " " + Float.floatToRawIntBits(i.floatValue()) + " "
                + Double.doubleToRawLongBits(i.doubleValue()) + " " + i.shortValue() + " " + i.byteValue());
        p(i.equals(new AtomicInteger(-1234567)) + " " + i.equals(i));
        try {
            i.updateAndGet(null);
        } catch (NullPointerException e) {
            p("npe " + e.getMessage() + " " + i);
        }
        try {
            i.getAndAccumulate(1, (a, b) -> {
                throw new IllegalStateException("from function");
            });
        } catch (IllegalStateException e) {
            p(e.getMessage() + " " + i);
        }
    }

    static void longs() {
        AtomicLong l = new AtomicLong();
        p(l + " " + new AtomicLong(-5L));
        p(l.getAndIncrement() + " " + l.incrementAndGet() + " " + l.getAndDecrement() + " " + l.decrementAndGet());
        p(l.getAndAdd(1L << 40) + " " + l.addAndGet(-3) + " " + l.get());
        p(l.compareAndSet(5, 1) + " " + l.compareAndSet((1L << 40) - 3, 1) + " " + l);
        p(l.weakCompareAndSet(0, 2) + " " + l.weakCompareAndSet(1, 2) + " " + l);
        p(l.getAndSet(9) + " " + l);
        l.set(Long.MAX_VALUE);
        p(l.incrementAndGet() + " " + l.getAndDecrement() + " " + l);
        l.lazySet(4);
        p(l.getAndUpdate(x -> x * 3) + " " + l.updateAndGet(x -> x + 1) + " " + l);
        p(l.getAndAccumulate(2, (a, b) -> a - b) + " " + l.accumulateAndGet(5, (a, b) -> a * 100 + b) + " " + l);
        l.set(0x123456789abcdefL);
        p(l.intValue() + " " + l.longValue() + " " + Float.floatToRawIntBits(l.floatValue()) + " "
                + Double.doubleToRawLongBits(l.doubleValue()) + " " + l.shortValue() + " " + l.byteValue());
        p(l.equals(new AtomicLong(0x123456789abcdefL)) + " " + l.equals(l));
    }

    static void references() {
        AtomicReference<String> r = new AtomicReference<>();
        p(r + " " + r.get() + " " + new AtomicReference<>("x"));
        String a = new String("a");
        String sameText = new String("a");
        p(r.compareAndSet(a, a) + " " + r.compareAndSet(null, a) + " " + r);
        p(r.compareAndSet(sameText, "b") + " " + r.compareAndSet(a, "b") + " " + r);
        p(r.weakCompareAndSet("zz", "c") + " " + r.weakCompareAndSet("b", "c") + " " + r);
        p(r.getAndSet(null) + " " + r);
        r.set("d");
        r.lazySet("e");
        p(r.getAndUpdate(s -> s + "f") + " " + r.updateAndGet(s -> s + "g") + " " + r);
        p(r.getAndAccumulate("h", (s, t) -> t + s) + " " + r.accumulateAndGet("i", (s, t) -> s + t) + " " + r);
        AtomicReference<Object> self = new AtomicReference<>(5);
        p(self.get() + " " + self);
        p(r.equals(new AtomicReference<>(r.get())) + " " + r.equals(r));
        Object silent = new Object() {
            @Override
            public String toString() {
                return null;
            }
        };
        p(new AtomicReference<>(silent).toString() == null);
    }

    static class Sub extends AtomicInteger {
        @Override
        public String toString() {
            return "Sub:" + get();
        }
    }

    public static void main(String[] args) {
        booleans();
        ints();
        longs();
        references();
        Sub s = new Sub();
        p(s.incrementAndGet() + " " + s);
    }
}
