package thread;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/** What the updaters reject, and with which exception, at creation and at use. */
@SuppressWarnings({"unchecked", "rawtypes"})
public class AtomicUpdaterErrors {
    interface Op {
        Object run() throws Exception;
    }

    static void attempt(String name, Op op) {
        try {
            Object r = op.run();
            System.out.println(name + ": ok"
                    + (r instanceof Number || r instanceof Boolean || r instanceof String ? " " + r : ""));
        } catch (Throwable e) {
            System.out.println(name + ": " + e.getClass().getName() + ": " + e.getMessage());
            for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
                System.out.println("  cause " + c.getClass().getName() + ": " + c.getMessage());
            }
        }
    }

    static class Holder {
        volatile int i;
        volatile long l;
        volatile Object o;
        volatile String s;
        volatile Integer boxed;
        volatile short sh;
        volatile byte by;
        volatile char ch;
        volatile boolean bo;
        volatile float fl;
        volatile double db;
        volatile int[] ints;
        volatile Object[] objects;
        volatile CharSequence seq;
        int plainInt;
        long plainLong;
        Object plainObject;
        String plainString;
        static volatile int staticInt;
        static volatile long staticLong;
        static volatile Object staticObject;
        static int staticPlain;
        static Object staticPlainObject;
        int[] plainArray;
    }

    static class Sub extends Holder {
    }

    static void intCreation() {
        attempt("int nulls", () -> AtomicIntegerFieldUpdater.newUpdater(null, null));
        attempt("int null class", () -> AtomicIntegerFieldUpdater.newUpdater(null, "i"));
        attempt("int null name", () -> AtomicIntegerFieldUpdater.newUpdater(Holder.class, null));
        attempt("int missing", () -> AtomicIntegerFieldUpdater.newUpdater(Holder.class, "nope"));
        attempt("int inherited", () -> AtomicIntegerFieldUpdater.newUpdater(Sub.class, "i"));
        attempt("int non volatile", () -> AtomicIntegerFieldUpdater.newUpdater(Holder.class, "plainInt"));
        attempt("int static", () -> AtomicIntegerFieldUpdater.newUpdater(Holder.class, "staticInt"));
        attempt("int static plain", () -> AtomicIntegerFieldUpdater.newUpdater(Holder.class, "staticPlain"));
        for (String f : new String[] {"l", "o", "boxed", "sh", "by", "ch", "bo", "fl", "db", "ints", "plainLong"}) {
            attempt("int on " + f, () -> AtomicIntegerFieldUpdater.newUpdater(Holder.class, f));
        }
        attempt("int on primitive class", () -> AtomicIntegerFieldUpdater.newUpdater(int.class, "i"));
        attempt("int on array class", () -> AtomicIntegerFieldUpdater.newUpdater(int[].class, "length"));
        attempt("int on interface", () -> AtomicIntegerFieldUpdater.newUpdater(Runnable.class, "x"));
    }

    static void longCreation() {
        attempt("long nulls", () -> AtomicLongFieldUpdater.newUpdater(null, null));
        attempt("long null class", () -> AtomicLongFieldUpdater.newUpdater(null, "l"));
        attempt("long null name", () -> AtomicLongFieldUpdater.newUpdater(Holder.class, null));
        attempt("long missing", () -> AtomicLongFieldUpdater.newUpdater(Holder.class, "nope"));
        attempt("long inherited", () -> AtomicLongFieldUpdater.newUpdater(Sub.class, "l"));
        attempt("long non volatile", () -> AtomicLongFieldUpdater.newUpdater(Holder.class, "plainLong"));
        attempt("long static", () -> AtomicLongFieldUpdater.newUpdater(Holder.class, "staticLong"));
        for (String f : new String[] {"i", "o", "sh", "db", "plainInt"}) {
            attempt("long on " + f, () -> AtomicLongFieldUpdater.newUpdater(Holder.class, f));
        }
    }

    static AtomicReferenceFieldUpdater ref(Class<?> t, Class<?> v, String f) {
        return AtomicReferenceFieldUpdater.newUpdater((Class) t, (Class) v, f);
    }

    static void refCreation() {
        attempt("ref nulls", () -> ref(null, null, null));
        attempt("ref null class", () -> ref(null, Object.class, "o"));
        attempt("ref null vclass", () -> ref(Holder.class, null, "o"));
        attempt("ref null name", () -> ref(Holder.class, Object.class, null));
        attempt("ref missing", () -> ref(Holder.class, Object.class, "nope"));
        attempt("ref inherited", () -> ref(Sub.class, Object.class, "o"));
        attempt("ref non volatile", () -> ref(Holder.class, Object.class, "plainObject"));
        attempt("ref non volatile wrong type", () -> ref(Holder.class, String.class, "plainObject"));
        attempt("ref static", () -> ref(Holder.class, Object.class, "staticObject"));
        attempt("ref static wrong type", () -> ref(Holder.class, String.class, "staticObject"));
        attempt("ref field narrower", () -> ref(Holder.class, Object.class, "s"));
        attempt("ref field wider", () -> ref(Holder.class, String.class, "o"));
        attempt("ref unrelated", () -> ref(Holder.class, Integer.class, "s"));
        attempt("ref interface wider", () -> ref(Holder.class, CharSequence.class, "s"));
        attempt("ref on interface field", () -> ref(Holder.class, CharSequence.class, "seq"));
        attempt("ref on int with int", () -> ref(Holder.class, int.class, "i"));
        attempt("ref on int with Integer", () -> ref(Holder.class, Integer.class, "i"));
        attempt("ref on int with Object", () -> ref(Holder.class, Object.class, "i"));
        attempt("ref on Integer with int", () -> ref(Holder.class, int.class, "boxed"));
        attempt("ref on array", () -> ref(Holder.class, int[].class, "ints"));
        attempt("ref on array with Object", () -> ref(Holder.class, Object.class, "ints"));
        attempt("ref on Object[] with String[]", () -> ref(Holder.class, String[].class, "objects"));
        attempt("ref void", () -> ref(Holder.class, void.class, "o"));
        attempt("ref on plain int with int", () -> ref(Holder.class, int.class, "plainInt"));
        attempt("ref on static int with int", () -> ref(Holder.class, int.class, "staticInt"));
        attempt("ref static plain", () -> ref(Holder.class, Object.class, "staticPlainObject"));
        attempt("ref static plain wrong", () -> ref(Holder.class, String.class, "staticPlainObject"));
        attempt("ref plain array", () -> ref(Holder.class, int[].class, "plainArray"));
    }

    static void intUse() {
        AtomicIntegerFieldUpdater u = AtomicIntegerFieldUpdater.newUpdater(Holder.class, "i");
        Object wrong = "not a holder";
        attempt("use int get null", () -> u.get(null));
        attempt("use int set null", () -> {
            u.set(null, 1);
            return null;
        });
        attempt("use int lazySet null", () -> {
            u.lazySet(null, 1);
            return null;
        });
        attempt("use int cas null", () -> u.compareAndSet(null, 0, 1));
        attempt("use int weak null", () -> u.weakCompareAndSet(null, 0, 1));
        attempt("use int getAndSet null", () -> u.getAndSet(null, 1));
        attempt("use int getAndAdd null", () -> u.getAndAdd(null, 1));
        attempt("use int incrementAndGet null", () -> u.incrementAndGet(null));
        attempt("use int updateAndGet null", () -> u.updateAndGet(null, x -> x));
        attempt("use int get wrong", () -> u.get(wrong));
        attempt("use int set wrong", () -> {
            u.set(wrong, 1);
            return null;
        });
        attempt("use int lazySet wrong", () -> {
            u.lazySet(wrong, 1);
            return null;
        });
        attempt("use int cas wrong", () -> u.compareAndSet(wrong, 0, 1));
        attempt("use int weak wrong", () -> u.weakCompareAndSet(wrong, 0, 1));
        attempt("use int getAndSet wrong", () -> u.getAndSet(wrong, 1));
        attempt("use int getAndAdd wrong", () -> u.getAndAdd(wrong, 1));
        attempt("use int getAndIncrement wrong", () -> u.getAndIncrement(wrong));
        attempt("use int accumulateAndGet wrong", () -> u.accumulateAndGet(wrong, 1, Integer::sum));
        attempt("use int updateAndGet null function", () -> u.updateAndGet(new Holder(), null));
    }

    static void longUse() {
        AtomicLongFieldUpdater u = AtomicLongFieldUpdater.newUpdater(Holder.class, "l");
        Object wrong = Integer.valueOf(1);
        attempt("use long get null", () -> u.get(null));
        attempt("use long set null", () -> {
            u.set(null, 1);
            return null;
        });
        attempt("use long cas null", () -> u.compareAndSet(null, 0, 1));
        attempt("use long getAndSet null", () -> u.getAndSet(null, 1));
        attempt("use long getAndAdd null", () -> u.getAndAdd(null, 1));
        attempt("use long get wrong", () -> u.get(wrong));
        attempt("use long set wrong", () -> {
            u.set(wrong, 1);
            return null;
        });
        attempt("use long lazySet wrong", () -> {
            u.lazySet(wrong, 1);
            return null;
        });
        attempt("use long cas wrong", () -> u.compareAndSet(wrong, 0, 1));
        attempt("use long weak wrong", () -> u.weakCompareAndSet(wrong, 0, 1));
        attempt("use long getAndAdd wrong", () -> u.getAndAdd(wrong, 1));
    }

    static void refUse() {
        AtomicReferenceFieldUpdater u = ref(Holder.class, String.class, "s");
        Object wrong = new Object();
        Holder h = new Holder();
        attempt("use ref get null", () -> u.get(null));
        attempt("use ref set null", () -> {
            u.set(null, "v");
            return null;
        });
        attempt("use ref cas null", () -> u.compareAndSet(null, "e", "v"));
        attempt("use ref getAndSet null", () -> u.getAndSet(null, "v"));
        attempt("use ref get wrong", () -> u.get(wrong));
        attempt("use ref set wrong", () -> {
            u.set(wrong, "v");
            return null;
        });
        attempt("use ref lazySet wrong", () -> {
            u.lazySet(wrong, "v");
            return null;
        });
        attempt("use ref cas wrong", () -> u.compareAndSet(wrong, "e", "v"));
        attempt("use ref weak wrong", () -> u.weakCompareAndSet(wrong, "e", "v"));
        attempt("use ref getAndSet wrong", () -> u.getAndSet(wrong, "v"));
        attempt("use ref set wrong value", () -> {
            u.set(h, Integer.valueOf(1));
            return null;
        });
        attempt("use ref lazySet wrong value", () -> {
            u.lazySet(h, Integer.valueOf(1));
            return null;
        });
        attempt("use ref cas wrong update", () -> u.compareAndSet(h, null, Integer.valueOf(1)));
        attempt("use ref cas wrong update no match", () -> u.compareAndSet(h, "other", Integer.valueOf(1)));
        attempt("use ref cas wrong expect", () -> u.compareAndSet(h, Integer.valueOf(1), "v"));
        attempt("use ref cas wrong both", () -> u.compareAndSet(h, Integer.valueOf(1), Integer.valueOf(2)));
        attempt("use ref weak wrong update", () -> u.weakCompareAndSet(h, null, Integer.valueOf(1)));
        attempt("use ref getAndSet wrong value", () -> u.getAndSet(h, Integer.valueOf(1)));
        attempt("use ref both wrong", () -> {
            u.set(wrong, Integer.valueOf(1));
            return null;
        });
        attempt("use ref value after", () -> h.s == null ? "still null" : "changed");
        attempt("use ref null value", () -> {
            u.set(h, null);
            return u.get(h) == null ? "null" : "not null";
        });
    }

    public static void main(String[] args) {
        intCreation();
        longCreation();
        refCreation();
        intUse();
        longUse();
        refUse();
    }
}
