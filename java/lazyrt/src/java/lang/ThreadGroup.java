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
    /** Subgroups, oldest first. */
    private ThreadGroup[] groups = NO_GROUPS;
    private int groupCount;
    /** Threads made here and not started: they hold a daemon group, but not against destroy (observed). */
    private int unstarted;
    /** Once set, both lists stay empty, so whatever reads them needs no check. */
    private boolean destroyed;

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

    /** Called when a thread is made, and for good: a constructor that fails later still counts (observed). */
    synchronized void addUnstarted() {
        refuseIfDestroyed();
        unstarted++;
    }

    /** Called when a thread starts. */
    synchronized void add(Thread t) {
        refuseIfDestroyed();
        if (threadCount == threads.length) {
            threads = java.util.Arrays.copyOf(threads, threadCount * 2);
        }
        threads[threadCount++] = t;
        unstarted--;
    }

    /** Called when a thread could not start: it is unstarted again, so the group stays. */
    synchronized void startFailed(Thread t) {
        take(t);
        unstarted++;
    }

    /** Called when a thread ends. */
    void remove(Thread t) {
        synchronized (this) {
            take(t);
            if (!endIfIdleDaemon()) {
                return;
            }
        }
        parent.removeGroup(this);
    }

    /** Closes the gap to keep the order. */
    private void take(Thread t) {
        for (int i = 0; i < threadCount; i++) {
            if (threads[i] == t) {
                System.arraycopy(threads, i + 1, threads, i, threadCount - i - 1);
                threads[--threadCount] = null;
                return;
            }
        }
    }

    private void refuseIfDestroyed() {
        if (destroyed) {
            throw new IllegalThreadStateException();
        }
    }

    /**
     * The oracle's system and main groups hold VM threads, even after main has returned,
     * so they refuse destroy (observed); here nothing else would stop it. An idle daemon
     * main group is the one difference (threads.md).
     */
    private boolean holdsVmThreads() {
        return parent == null || this == Thread.MAIN_GROUP;
    }

    /** Whether this group just ended; the caller then leaves the parent, outside this lock. */
    private boolean endIfIdleDaemon() {
        if (!daemon || threadCount > 0 || unstarted > 0 || groupCount > 0 || holdsVmThreads()) {
            return false;
        }
        destroyed = true;
        return true;
    }

    /**
     * One lock at a time, so that destroy and a thread's end, going opposite ways
     * through the tree, cannot deadlock. A group that fails on a busy subgroup stays
     * destroyed and stays in its parent's list; the subgroups it has not reached
     * live on, cut off from it (observed).
     */
    public final void destroy() {
        ThreadGroup[] subs;
        synchronized (this) {
            refuseIfDestroyed();
            if (threadCount > 0 || holdsVmThreads()) {
                throw new IllegalThreadStateException();
            }
            subs = subgroups();
            destroyed = true;
            groups = NO_GROUPS;
            groupCount = 0;
        }
        for (ThreadGroup g : subs) {
            g.destroy();
        }
        parent.removeGroup(this);
    }

    public synchronized boolean isDestroyed() {
        return destroyed;
    }

    private void removeGroup(ThreadGroup g) {
        synchronized (this) {
            // The list is already empty, and ending again would leave the parent's.
            if (destroyed) {
                return;
            }
            for (int i = 0; i < groupCount; i++) {
                if (groups[i] == g) {
                    System.arraycopy(groups, i + 1, groups, i, groupCount - i - 1);
                    groups[--groupCount] = null;
                    break;
                }
            }
            if (!endIfIdleDaemon()) {
                return;
            }
        }
        parent.removeGroup(this);
    }

    private synchronized void addGroup(ThreadGroup g) {
        refuseIfDestroyed();
        if (groupCount == groups.length) {
            // Not Arrays: the first currentThread comes here, and need not load it.
            ThreadGroup[] more = new ThreadGroup[Math.max(4, groupCount * 2)];
            System.arraycopy(groups, 0, more, 0, groupCount);
            groups = more;
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

    // Not through the two-argument forms, which a subclass may override (observed).
    public int enumerate(Thread[] list) {
        return collect(list, 0, true, true);
    }

    public int enumerate(Thread[] list, boolean recurse) {
        return collect(list, 0, recurse, true);
    }

    public int enumerate(ThreadGroup[] list) {
        return collect(list, 0, true, false);
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
        ThreadGroup[] subs;
        synchronized (this) {
            // Back to the start, even part way through a parent's listing or with no array (observed).
            if (destroyed) {
                return 0;
            }
            int room = into.length - at;
            subs = subgroups();
            int n = Math.min(room, wantThreads ? threadCount : groupCount);
            // Into a narrower array type, the oracle's failure names Thread, so one
            // store at a time, and has no message for groups, like arraycopy's (observed).
            if (wantThreads) {
                for (int i = 0; i < n; i++) {
                    into[at + i] = threads[i];
                }
            } else {
                System.arraycopy(groups, 0, into, at, n);
            }
            at += n;
        }
        if (recurse) {
            for (ThreadGroup g : subs) {
                at = g.collect(into, at, true, wantThreads);
            }
        }
        return at;
    }

    /**
     * Holds the lock across the subclass code it calls, and reads the list live,
     * so what that code adds or takes away counts; subgroups are read after (observed).
     */
    public final void interrupt() {
        ThreadGroup[] subs;
        synchronized (this) {
            for (int i = 0; i < threadCount; i++) {
                threads[i].interrupt();
            }
            subs = subgroups();
        }
        for (ThreadGroup g : subs) {
            g.interrupt();
        }
    }

    public void list() {
        list(System.out, 0);
    }

    /** As interrupt; and all of it goes to the stream that was current at the start (observed). */
    private void list(java.io.PrintStream out, int indent) {
        ThreadGroup[] subs;
        synchronized (this) {
            printIndented(out, indent, this);
            for (int i = 0; i < threadCount; i++) {
                printIndented(out, indent + 4, threads[i]);
            }
            subs = subgroups();
        }
        for (ThreadGroup g : subs) {
            g.list(out, indent + 4);
        }
    }

    /** The indent goes out first: a toString that prints or throws shows it (observed). */
    private static void printIndented(java.io.PrintStream out, int indent, Object o) {
        for (int i = 0; i < indent; i++) {
            out.print(" ");
        }
        out.println(o);
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
