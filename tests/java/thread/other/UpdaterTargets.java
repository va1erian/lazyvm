package thread.other;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/** Fields another package's updaters try to reach; see thread.AtomicUpdaterAccess. */
public class UpdaterTargets {
    public volatile int publicField;
    protected volatile int protectedField;
    volatile int packageField;
    private volatile int privateField;
    protected volatile long protectedLong;
    protected volatile Object protectedRef;
    protected volatile String protectedString;
    protected static volatile int protectedStatic;
    public static volatile int publicStatic;
    static volatile int packageStatic;
    private static volatile int privateStatic;

    /** An updater made here: the caller, not the user of the updater, is what is checked. */
    public static AtomicIntegerFieldUpdater<UpdaterTargets> privateUpdater() {
        return AtomicIntegerFieldUpdater.newUpdater(UpdaterTargets.class, "privateField");
    }

    public static AtomicIntegerFieldUpdater<UpdaterTargets> protectedUpdater() {
        return AtomicIntegerFieldUpdater.newUpdater(UpdaterTargets.class, "protectedField");
    }

    public static class Unrelated {
        public volatile int visible;
        public static volatile int publicStatic;
        protected volatile int protectedField;
        protected static volatile int protectedStatic;
        volatile int packageField;
        static volatile int packageStatic;
        private volatile int privateField;
        private static volatile int privateStatic;
    }

    static class Hidden {
        public volatile int visible;
        public static volatile int publicStatic;
        protected volatile int protectedField;
        protected static volatile int protectedStatic;
        volatile int packageField;
        static volatile int packageStatic;
        private volatile int privateField;
        private static volatile int privateStatic;
    }

    protected static class ProtectedNested {
        public volatile int visible;
        public static volatile int publicStatic;
        protected volatile int protectedField;
        protected static volatile int protectedStatic;
        volatile int packageField;
        static volatile int packageStatic;
        private volatile int privateField;
        private static volatile int privateStatic;
    }

    private static class PrivateNested {
        public volatile int visible;
        public static volatile int publicStatic;
        protected volatile int protectedField;
        protected static volatile int protectedStatic;
        volatile int packageField;
        static volatile int packageStatic;
        private volatile int privateField;
        private static volatile int privateStatic;
    }

    public static Class<?> protectedClass() {
        return ProtectedNested.class;
    }

    public static Class<?> privateClass() {
        return PrivateNested.class;
    }

    public static Class<?> hiddenClass() {
        return Hidden.class;
    }

    public static Class<?> topHiddenClass() {
        return TopHidden.class;
    }

    public static void main(String[] args) throws Exception {
        UpdaterTargets t = new UpdaterTargets();
        privateUpdater().set(t, 3);
        System.out.println(t.privateField);
    }
}

/** A top-level class that is not public, for what the refusal says about its members. */
class TopHidden {
    public volatile int visible;
    public static volatile int publicStatic;
    protected volatile int protectedField;
    protected static volatile int protectedStatic;
    volatile int packageField;
    static volatile int packageStatic;
    private volatile int privateField;
    private static volatile int privateStatic;
}
