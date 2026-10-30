package thread;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiFunction;
import thread.other.UpdaterTargets;

/** Which caller may make an updater for which field, and the protected-receiver rule. */
public class AtomicUpdaterAccess extends UpdaterTargets {
    interface Op {
        Object run() throws Exception;
    }

    static void attempt(String name, Op op) {
        try {
            Object r = op.run();
            System.out.println(name + ": ok" + (r instanceof Number || r instanceof Boolean ? " " + r : ""));
        } catch (Throwable e) {
            System.out.println(name + ": " + e.getClass().getName() + ": " + e.getMessage());
            for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
                System.out.println("  cause " + c.getClass().getName() + ": " + c.getMessage());
            }
        }
    }

    static void attemptQuiet(String name, Op op) {
        try {
            op.run();
            System.out.println(name + ": ok");
        } catch (Throwable e) {
            System.out.println(name + ": " + e.getClass().getName() + ", cause " + e.getCause().getClass().getName());
        }
    }

    private volatile int ownPrivate;

    static class Nested {
        private volatile int nestedPrivate;
        volatile int nestedPackage;
        protected volatile int nestedProtected;
        public volatile int nestedPublic;

        static Object makeOuter() {
            return AtomicIntegerFieldUpdater.newUpdater(AtomicUpdaterAccess.class, "ownPrivate");
        }

        static Object makeOwn() {
            return AtomicIntegerFieldUpdater.newUpdater(Nested.class, "nestedPrivate");
        }
    }

    private static class PrivateNested {
        public volatile int visible;
    }

    static class Child extends AtomicUpdaterAccess {
    }

    static class Sibling extends UpdaterTargets {
    }

    /** A protected field and a subclass in the same package: receivers are not restricted. */
    static class LocalBase {
        protected volatile int localProtected;
    }

    static class LocalSub extends LocalBase {
        static AtomicIntegerFieldUpdater<LocalBase> make() {
            return AtomicIntegerFieldUpdater.newUpdater(LocalBase.class, "localProtected");
        }
    }

    /** Not a subclass of anything it tries: protected fields are refused like the rest. */
    static class Stranger {
        static final String[] FIELDS = {"publicStatic", "protectedField", "protectedStatic", "packageField",
            "packageStatic", "privateField", "privateStatic"};

        static void reach(String what, Class<?> c) {
            for (String f : FIELDS) {
                attempt(what + " " + f, () -> AtomicIntegerFieldUpdater.newUpdater(c, f));
            }
            attempt(what + " visible", () -> AtomicIntegerFieldUpdater.newUpdater(c, "visible"));
        }

        static void all() {
            attempt("stranger protected", () -> AtomicIntegerFieldUpdater.newUpdater(UpdaterTargets.class, "protectedField"));
            attempt("stranger protected static",
                    () -> AtomicIntegerFieldUpdater.newUpdater(UpdaterTargets.class, "protectedStatic"));
            attempt("stranger protected long", () -> AtomicLongFieldUpdater.newUpdater(UpdaterTargets.class, "protectedLong"));
            attempt("stranger protected ref",
                    () -> AtomicReferenceFieldUpdater.newUpdater(UpdaterTargets.class, Object.class, "protectedRef"));
            attempt("stranger public static", () -> AtomicIntegerFieldUpdater.newUpdater(UpdaterTargets.class, "publicStatic"));
            attempt("stranger package static", () -> AtomicIntegerFieldUpdater.newUpdater(UpdaterTargets.class, "packageStatic"));
            attempt("stranger private static", () -> AtomicIntegerFieldUpdater.newUpdater(UpdaterTargets.class, "privateStatic"));
            reach("public nested", UpdaterTargets.Unrelated.class);
            reach("package nested", UpdaterTargets.hiddenClass());
            reach("protected nested", UpdaterTargets.protectedClass());
            reach("private nested", UpdaterTargets.privateClass());
            reach("package top level", UpdaterTargets.topHiddenClass());
        }
    }

    /** Which check of a reference updater comes first when both would fail. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void referenceCheckOrder() {
        AtomicReferenceFieldUpdater strings =
                AtomicReferenceFieldUpdater.newUpdater(UpdaterTargets.class, String.class, "protectedString");
        Object wrongValue = Integer.valueOf(1);
        UpdaterTargets base = new UpdaterTargets();
        attempt("order set", () -> {
            strings.set(base, wrongValue);
            return null;
        });
        attempt("order lazySet", () -> {
            strings.lazySet(base, wrongValue);
            return null;
        });
        attempt("order cas", () -> strings.compareAndSet(base, null, wrongValue));
        attempt("order weak", () -> strings.weakCompareAndSet(base, null, wrongValue));
        attempt("order getAndSet", () -> strings.getAndSet(base, wrongValue));
        attempt("order null receiver", () -> {
            strings.set(null, wrongValue);
            return null;
        });
        attempt("order good receiver", () -> {
            strings.set(new AtomicUpdaterAccess(), wrongValue);
            return null;
        });
    }

    static void kinds() {
        attempt("own private", () -> AtomicIntegerFieldUpdater.newUpdater(AtomicUpdaterAccess.class, "ownPrivate"));
        attempt("nested private from outer",
                () -> AtomicIntegerFieldUpdater.newUpdater(Nested.class, "nestedPrivate"));
        attempt("outer private from nested", Nested::makeOuter);
        attempt("nested private from nested", Nested::makeOwn);
        attempt("nested package", () -> AtomicIntegerFieldUpdater.newUpdater(Nested.class, "nestedPackage"));
        attempt("nested protected", () -> AtomicIntegerFieldUpdater.newUpdater(Nested.class, "nestedProtected"));
        attempt("nested public", () -> AtomicIntegerFieldUpdater.newUpdater(Nested.class, "nestedPublic"));
        attempt("private nested public", () -> AtomicIntegerFieldUpdater.newUpdater(PrivateNested.class, "visible"));
    }

    static void otherPackage() {
        attempt("other public", () -> AtomicIntegerFieldUpdater.newUpdater(UpdaterTargets.class, "publicField"));
        attempt("other package", () -> AtomicIntegerFieldUpdater.newUpdater(UpdaterTargets.class, "packageField"));
        attempt("other private", () -> AtomicIntegerFieldUpdater.newUpdater(UpdaterTargets.class, "privateField"));
        attempt("other public class", () -> AtomicIntegerFieldUpdater.newUpdater(UpdaterTargets.Unrelated.class, "visible"));
        attempt("other hidden class",
                () -> AtomicIntegerFieldUpdater.newUpdater(UpdaterTargets.hiddenClass(), "visible"));
        attempt("other protected class",
                () -> AtomicIntegerFieldUpdater.newUpdater(UpdaterTargets.protectedClass(), "visible"));
        attempt("other private class",
                () -> AtomicIntegerFieldUpdater.newUpdater(UpdaterTargets.privateClass(), "visible"));
        attempt("long private", () -> AtomicLongFieldUpdater.newUpdater(UpdaterTargets.class, "privateField"));
        attempt("ref private", () -> AtomicReferenceFieldUpdater.newUpdater(UpdaterTargets.class, Object.class, "privateField"));
        attempt("no access and not there", () -> AtomicIntegerFieldUpdater.newUpdater(UpdaterTargets.class, "absent"));
    }

    /** A protected field of another package's class: the caller is a subclass here. */
    static void protectedRule() {
        attempt("protected via superclass",
                () -> AtomicIntegerFieldUpdater.newUpdater(UpdaterTargets.class, "protectedField"));
        AtomicIntegerFieldUpdater<UpdaterTargets> viaBase =
                AtomicIntegerFieldUpdater.newUpdater(UpdaterTargets.class, "protectedField");
        AtomicUpdaterAccess self = new AtomicUpdaterAccess();
        attempt("receiver is caller", () -> viaBase.incrementAndGet(self));
        attempt("receiver is caller subclass", () -> viaBase.incrementAndGet(new Child()));
        attempt("receiver is the base", () -> viaBase.incrementAndGet(new UpdaterTargets()));
        attempt("get receiver is the base", () -> viaBase.get(new UpdaterTargets()));
        attempt("set receiver is the base", () -> {
            viaBase.set(new UpdaterTargets(), 1);
            return null;
        });
        attempt("lazySet receiver is the base", () -> {
            viaBase.lazySet(new UpdaterTargets(), 1);
            return null;
        });
        attempt("cas receiver is the base", () -> viaBase.compareAndSet(new UpdaterTargets(), 1, 2));
        attempt("weak receiver is the base", () -> viaBase.weakCompareAndSet(new UpdaterTargets(), 1, 2));
        attempt("getAndSet receiver is the base", () -> viaBase.getAndSet(new UpdaterTargets(), 2));
        attempt("getAndAdd receiver is the base", () -> viaBase.getAndAdd(new UpdaterTargets(), 2));
        attempt("null receiver", () -> viaBase.get(null));
        attempt("null receiver set", () -> {
            viaBase.set(null, 1);
            return null;
        });
        attempt("null receiver cas", () -> viaBase.compareAndSet(null, 1, 2));
        attempt("null receiver getAndAdd", () -> viaBase.getAndAdd(null, 2));
        @SuppressWarnings({"unchecked", "rawtypes"})
        AtomicIntegerFieldUpdater<Object> rawBase = (AtomicIntegerFieldUpdater) viaBase;
        attempt("wrong receiver", () -> rawBase.get("text"));
        attempt("wrong receiver set", () -> {
            rawBase.set("text", 1);
            return null;
        });
        attempt("wrong receiver cas", () -> rawBase.compareAndSet("text", 1, 2));
        attempt("sibling receiver", () -> viaBase.get(new Sibling()));
        AtomicLongFieldUpdater<UpdaterTargets> longs =
                AtomicLongFieldUpdater.newUpdater(UpdaterTargets.class, "protectedLong");
        attempt("long receiver is caller", () -> longs.incrementAndGet(self));
        attempt("long receiver is the base", () -> longs.incrementAndGet(new UpdaterTargets()));
        attempt("long get receiver is the base", () -> longs.get(new UpdaterTargets()));
        AtomicReferenceFieldUpdater<UpdaterTargets, Object> refs =
                AtomicReferenceFieldUpdater.newUpdater(UpdaterTargets.class, Object.class, "protectedRef");
        attempt("ref receiver is caller", () -> refs.compareAndSet(self, null, "x"));
        attempt("ref receiver is the base", () -> refs.compareAndSet(new UpdaterTargets(), null, "x"));
        attempt("ref null receiver", () -> refs.get(null));
        attempt("long null receiver", () -> longs.get(null));
        attempt("ref get receiver is the base", () -> refs.get(new UpdaterTargets()));
        attempt("ref set receiver is the base", () -> {
            refs.set(new UpdaterTargets(), "x");
            return null;
        });
        attempt("ref getAndSet receiver is the base", () -> refs.getAndSet(new UpdaterTargets(), "x"));
        attempt("inherited, through subclass class",
                () -> AtomicIntegerFieldUpdater.newUpdater(AtomicUpdaterAccess.class, "protectedField"));
        // Made in the field's own package: no restriction on the receiver.
        attempt("same package subclass", () -> LocalSub.make().incrementAndGet(new LocalBase()));
        attempt("made at home", () -> UpdaterTargets.protectedUpdater().incrementAndGet(new UpdaterTargets()));
        attempt("private made at home", () -> UpdaterTargets.privateUpdater().incrementAndGet(new UpdaterTargets()));
    }

    /** The caller seen through reflection and method references. */
    static void callers() throws Exception {
        Method m = AtomicIntegerFieldUpdater.class.getMethod("newUpdater", Class.class, String.class);
        attempt("reflective own private", () -> m.invoke(null, AtomicUpdaterAccess.class, "ownPrivate"));
        attempt("reflective other private", () -> m.invoke(null, UpdaterTargets.class, "privateField"));
        BiFunction<Class<AtomicUpdaterAccess>, String, Object> ref = AtomicIntegerFieldUpdater::newUpdater;
        // Refused because the caller is the lambda's own class, whose name is not stable.
        attemptQuiet("method reference own private", () -> ref.apply(AtomicUpdaterAccess.class, "ownPrivate"));
        BiFunction<Class<UpdaterTargets>, String, Object> otherRef = AtomicIntegerFieldUpdater::newUpdater;
        attemptQuiet("method reference other private", () -> otherRef.apply(UpdaterTargets.class, "privateField"));
        BiFunction<Class<UpdaterTargets>, String, Object> publicRef = AtomicIntegerFieldUpdater::newUpdater;
        attemptQuiet("method reference other public", () -> publicRef.apply(UpdaterTargets.class, "publicField"));
        BiFunction<Class<Nested>, String, Object> packageRef = AtomicIntegerFieldUpdater::newUpdater;
        attemptQuiet("method reference same package", () -> packageRef.apply(Nested.class, "nestedPackage"));
    }

    public static void main(String[] args) throws Exception {
        kinds();
        otherPackage();
        protectedRule();
        referenceCheckOrder();
        Stranger.all();
        callers();
    }
}
