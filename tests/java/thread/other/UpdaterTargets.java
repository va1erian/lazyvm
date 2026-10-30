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

    /** An updater made here: the caller, not the user of the updater, is what is checked. */
    public static AtomicIntegerFieldUpdater<UpdaterTargets> privateUpdater() {
        return AtomicIntegerFieldUpdater.newUpdater(UpdaterTargets.class, "privateField");
    }

    public static AtomicIntegerFieldUpdater<UpdaterTargets> protectedUpdater() {
        return AtomicIntegerFieldUpdater.newUpdater(UpdaterTargets.class, "protectedField");
    }

    public static class Unrelated {
        public volatile int visible;
    }

    static class Hidden {
        public volatile int visible;
    }

    protected static class ProtectedNested {
        public volatile int visible;
    }

    private static class PrivateNested {
        public volatile int visible;
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

    public static void main(String[] args) throws Exception {
        UpdaterTargets t = new UpdaterTargets();
        privateUpdater().set(t, 3);
        System.out.println(t.privateField);
    }
}
