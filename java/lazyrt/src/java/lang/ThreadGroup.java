package java.lang;

/**
 * A named set of threads and subgroups: the name, the parent, the priority
 * ceiling, the uncaught exception chain and who is in it. Plain arrays, so
 * that adding the main thread, perhaps with the heap full, allocates nothing.
 */
public class ThreadGroup implements Thread.UncaughtExceptionHandler {
    private static final ThreadGroup[] NO_GROUPS = new ThreadGroup[0];

    private final String name;
    private final ThreadGroup parent;
    private int maxPriority;
    private boolean daemon;
    /** Started threads that have not ended, oldest first. */
    private Thread[] threads = new Thread[4];
    private int threadCount;
    /** Subgroups, oldest first. They stay for good: there is no destroy() yet (threads.md). */
    private ThreadGroup[] groups = NO_GROUPS;
    private int groupCount;

    /** The root group, which has no parent. */
    ThreadGroup() {
        this.name = "system";
        this.parent = null;
        this.maxPriority = Thread.MAX_PRIORITY;
    }

    public ThreadGroup(String name) {
        this(Thread.currentThread().getThreadGroup(), name);
    }

    public ThreadGroup(ThreadGroup parent, String name) {
        if (parent == null) {
            throw new NullPointerException();
        }
        this.name = name;
        this.parent = parent;
        this.maxPriority = parent.maxPriority;
        this.daemon = parent.daemon;
        parent.addGroup(this);
    }

    /** Called when a thread starts. */
    synchronized void add(Thread t) {
        if (threadCount == threads.length) {
            threads = java.util.Arrays.copyOf(threads, threadCount * 2);
        }
        threads[threadCount++] = t;
    }

    /** Called when a thread ends or fails to start; closes the gap to keep the order. */
    synchronized void remove(Thread t) {
        for (int i = 0; i < threadCount; i++) {
            if (threads[i] == t) {
                System.arraycopy(threads, i + 1, threads, i, threadCount - i - 1);
                threads[--threadCount] = null;
                return;
            }
        }
    }

    private synchronized void addGroup(ThreadGroup g) {
        if (groupCount == groups.length) {
            groups = java.util.Arrays.copyOf(groups, Math.max(4, groupCount * 2));
        }
        groups[groupCount++] = g;
    }

    private synchronized ThreadGroup[] subgroups() {
        // The empty array is never written to, so it needs no copy.
        return groupCount == 0 ? NO_GROUPS : java.util.Arrays.copyOf(groups, groupCount);
    }

    public int activeCount() {
        int n;
        synchronized (this) {
            n = threadCount;
        }
        for (ThreadGroup g : subgroups()) {
            n += g.activeCount();
        }
        return n;
    }

    public int activeGroupCount() {
        int n = 0;
        for (ThreadGroup g : subgroups()) {
            n += 1 + g.activeGroupCount();
        }
        return n;
    }

    public int enumerate(Thread[] list) {
        return enumerate(list, true);
    }

    public int enumerate(Thread[] list, boolean recurse) {
        return collect(list, 0, recurse, true);
    }

    public int enumerate(ThreadGroup[] list) {
        return enumerate(list, true);
    }

    public int enumerate(ThreadGroup[] list, boolean recurse) {
        return collect(list, 0, recurse, false);
    }

    /**
     * Stores this group's own threads or subgroups from `at`, then those of
     * each subgroup in turn (the order is observed); what does not fit is left
     * out. Returns where the next one would go.
     */
    private int collect(Object[] into, int at, boolean recurse, boolean wantThreads) {
        int room = into.length - at;
        ThreadGroup[] subs;
        synchronized (this) {
            subs = subgroups();
            int n = Math.min(room, wantThreads ? threadCount : groupCount);
            System.arraycopy(wantThreads ? threads : groups, 0, into, at, n);
            at += n;
        }
        if (recurse) {
            for (ThreadGroup g : subs) {
                at = g.collect(into, at, true, wantThreads);
            }
        }
        return at;
    }

    /** Every thread in this group and below, for Thread.getAllStackTraces. */
    Thread[] allThreads() {
        Thread[] all = new Thread[activeCount() + 1];
        int n = enumerate(all);
        // Full: more may have started since the count, so look again with more room.
        while (n == all.length) {
            all = new Thread[n * 2];
            n = enumerate(all);
        }
        return java.util.Arrays.copyOf(all, n);
    }

    public final String getName() {
        return name;
    }

    public final ThreadGroup getParent() {
        return parent;
    }

    public final int getMaxPriority() {
        return maxPriority;
    }

    public final void setMaxPriority(int pri) {
        if (pri < Thread.MIN_PRIORITY || pri > Thread.MAX_PRIORITY) {
            return;
        }
        maxPriority = parent != null ? Math.min(pri, parent.maxPriority) : pri;
        for (ThreadGroup g : subgroups()) {
            g.setMaxPriority(pri);
        }
    }

    public final boolean isDaemon() {
        return daemon;
    }

    public final void setDaemon(boolean daemon) {
        this.daemon = daemon;
    }

    public final boolean parentOf(ThreadGroup g) {
        for (; g != null; g = g.parent) {
            if (g == this) {
                return true;
            }
        }
        return false;
    }

    public final void checkAccess() {
    }

    /**
     * Passes the exception up to the root; the root uses the default
     * handler, or prints the exception the way the launcher reports one.
     */
    public void uncaughtException(Thread t, Throwable e) {
        if (parent != null) {
            parent.uncaughtException(t, e);
            return;
        }
        Thread.UncaughtExceptionHandler h = Thread.getDefaultUncaughtExceptionHandler();
        if (h != null) {
            h.uncaughtException(t, e);
        } else if (!(e instanceof ThreadDeath)) {
            System.err.print("Exception in thread \"" + t.getName() + "\" ");
            e.printStackTrace(System.err);
        }
    }

    public String toString() {
        return getClass().getName() + "[name=" + name + ",maxpri=" + maxPriority + "]";
    }
}
