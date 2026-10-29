package java.lang;

/**
 * A named set of threads. LazyVM keeps only what threads use: the name,
 * the parent, the priority ceiling and the uncaught exception chain.
 */
public class ThreadGroup implements Thread.UncaughtExceptionHandler {
    private final String name;
    private final ThreadGroup parent;
    private int maxPriority;
    private boolean daemon;

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
