package java.util.concurrent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Every queued task of one pool: a plain deque per worker and a list of submissions, plus the table
 * of workers by index. All of it is guarded by the {@link ForkJoinCore}'s monitor, which the core's
 * calls already hold and the last group of methods here takes for other callers. A running count of the deques' tasks spares looking through all
 * workers to learn whether any is queued, which a pool with thousands of blocked workers would pay
 * on every take.
 */
final class ForkJoinQueues {
    private final Object monitor;
    private final boolean asyncMode;
    private final ArrayDeque<ForkJoinTask<?>> submissions = new ArrayDeque<ForkJoinTask<?>>();
    /** By pool index; a slot is empty once its worker ended. */
    private final ArrayList<ForkJoinWorkerThread> workers = new ArrayList<ForkJoinWorkerThread>();
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<Integer>();
    /** Where the last steal found work, so a run of steals does not walk the table each time. */
    private int stealCursor;
    private int localTasks;
    private long steals;

    ForkJoinQueues(Object monitor, boolean asyncMode) {
        this.monitor = monitor;
        this.asyncMode = asyncMode;
    }

    int takeSlot(ForkJoinWorkerThread w) {
        Integer slot = freeSlots.pollLast();
        if (slot == null) {
            workers.add(w);
            return workers.size() - 1;
        }
        workers.set(slot, w);
        return slot;
    }

    void freeSlot(int slot) {
        workers.set(slot, null);
        freeSlots.addLast(slot);
    }

    List<ForkJoinWorkerThread> workers() {
        return workers;
    }

    void addSubmission(ForkJoinTask<?> t) {
        submissions.addLast(t);
    }

    void pushLocal(ForkJoinWorkerThread w, ForkJoinTask<?> t) {
        w.local.addLast(t);
        localTasks++;
    }

    ForkJoinTask<?> ownNext(ForkJoinWorkerThread w) {
        ForkJoinTask<?> t = asyncMode ? w.local.pollFirst() : w.local.pollLast();
        if (t != null) {
            localTasks--;
        }
        return t;
    }

    ForkJoinTask<?> peekOwnNext(ForkJoinWorkerThread w) {
        return asyncMode ? w.local.peekFirst() : w.local.peekLast();
    }

    /** The oldest of another worker's tasks, or else the oldest submission. */
    ForkJoinTask<?> takeForeign(ForkJoinWorkerThread w) {
        if (localTasks > (w != null ? w.local.size() : 0)) {
            int n = workers.size();
            for (int i = 0; i < n; i++) {
                int at = (stealCursor + i) % n;
                ForkJoinWorkerThread other = workers.get(at);
                if (other != null && other != w && !other.local.isEmpty()) {
                    stealCursor = at;
                    localTasks--;
                    steals++;
                    return other.local.pollFirst();
                }
            }
        }
        return submissions.pollFirst();
    }

    /** Looks at the likely places first: this worker's newest, then its oldest. */
    boolean removeQueued(ForkJoinWorkerThread w, ForkJoinTask<?> t) {
        if (w != null) {
            boolean newest = w.local.peekLast() == t;
            if (newest ? w.local.pollLast() != null : w.local.removeFirstOccurrence(t)) {
                localTasks--;
                return true;
            }
        }
        if (localTasks > (w != null ? w.local.size() : 0)) {
            for (ForkJoinWorkerThread other : workers) {
                if (other != null && other != w && other.local.removeFirstOccurrence(t)) {
                    localTasks--;
                    return true;
                }
            }
        }
        return submissions.removeFirstOccurrence(t);
    }

    boolean unfork(ForkJoinWorkerThread w, ForkJoinTask<?> t) {
        ArrayDeque<ForkJoinTask<?>> q = w != null ? w.local : submissions;
        if (q.peekLast() != t) {
            return false;
        }
        q.pollLast();
        if (w != null) {
            localTasks--;
        }
        return true;
    }

    /** Moves what this worker has queued to {@code out}. */
    void drainLocal(ForkJoinWorkerThread w, List<ForkJoinTask<?>> out) {
        localTasks -= w.local.size();
        out.addAll(w.local);
        w.local.clear();
    }

    /** Moves everything queued anywhere to {@code out}, submissions first. */
    void drainAll(List<ForkJoinTask<?>> out) {
        out.addAll(submissions);
        submissions.clear();
        for (ForkJoinWorkerThread w : workers) {
            if (w != null) {
                drainLocal(w, out);
            }
        }
    }

    boolean anyQueued() {
        return localTasks > 0 || !submissions.isEmpty();
    }

    int localTasks() {
        return localTasks;
    }

    int submissions() {
        return submissions.size();
    }

    long steals() {
        return steals;
    }

    // For callers outside the core, so these take the monitor.

    ForkJoinTask<?> pollForeign() {
        synchronized (monitor) {
            return takeForeign(null);
        }
    }

    boolean unforkNewest(ForkJoinWorkerThread w, ForkJoinTask<?> t) {
        synchronized (monitor) {
            return unfork(w, t);
        }
    }

    int ownCount(ForkJoinWorkerThread w) {
        synchronized (monitor) {
            return w.local.size();
        }
    }

    ForkJoinTask<?> nextLocal(ForkJoinWorkerThread w, boolean remove) {
        synchronized (monitor) {
            return remove ? ownNext(w) : peekOwnNext(w);
        }
    }

    ForkJoinTask<?> pollAny(ForkJoinWorkerThread w) {
        synchronized (monitor) {
            ForkJoinTask<?> t = ownNext(w);
            return t != null ? t : takeForeign(w);
        }
    }

    long stealCount() {
        synchronized (monitor) {
            return steals;
        }
    }

    long localTaskCount() {
        synchronized (monitor) {
            return localTasks;
        }
    }

    int submissionCount() {
        synchronized (monitor) {
            return submissions.size();
        }
    }

    ForkJoinTask<?> takeSubmission() {
        synchronized (monitor) {
            return submissions.pollFirst();
        }
    }

    /** Outside the monitor, as {@code cancel} is the user's to override. One that throws stops no other. */
    static void cancelAll(List<ForkJoinTask<?>> tasks) {
        for (ForkJoinTask<?> t : tasks) {
            try {
                t.cancel(false);
            } catch (Throwable theOthersMustStillBeCancelled) {
                continue;
            }
        }
    }
}
