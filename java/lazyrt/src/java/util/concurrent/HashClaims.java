package java.util.concurrent;

/**
 * The claims on a {@link HashCore}, found by hash, so that a write pays for those of its own hash and
 * not for how many exist. All of this is guarded by the core's monitor, except {@link #mayHold}, which
 * may also be read without it, as a hint: every link it follows leads on to an end, at any moment.
 *
 * <p>What holds:
 * <ul>
 * <li>Ops on equal keys never run together, except on one thread. A thread lists its claim before it
 * compares keys, with that claim's monitor held, and compares only with claims listed before it. Of two
 * overlapping ops the later therefore sees the earlier's claim, and joins it instead of running. And so
 * no number of later arrivals, of any key, can make a comparing thread start over.
 * <li>A waiter is not a claim. Having found the claim of an equal key, a thread gives its own up, and
 * whoever had stopped behind that one looks again. So there is no queue of claims, and a thread going
 * straight from one op to the next finds the key free, as it would a monitor it had just left. The VM's
 * rule for any monitor keeps that from starving those blocked on it (threads.md, "Fairness").
 * <li>A thread that holds a claim on an equal key goes straight through, looking at nobody else: a
 * recursive update is a lost update, never a hang. Hence {@link #joined}'s order.
 * <li>No user code runs under the guard.
 * </ul>
 */
final class HashClaims {
    /** A lock on one key, listed for as long as anyone holds it or will. Who runs in it holds its monitor. */
    static final class Claim {
        final int hash;
        final Object key;
        /** Its place among all stakes while it is listed; else 0. */
        private long order;
        private Claim next;
        /** Who will still enter it, its first holder included. */
        private int users = 1;
        /** Its first holder found no earlier claim on the key. Until then nobody runs in it. */
        boolean open;

        Claim(int hash, Object key) {
            this.hash = hash;
            this.key = key;
        }
    }

    private final Object guard;
    private Claim[] slots;
    /** Read before {@link #mayHold}, to spare that call on a map with no claims. */
    int count;
    private long staked;

    HashClaims(Object guard) {
        this.guard = guard;
    }

    boolean mayHold(int hash) {
        Claim[] s = slots;
        if (s != null) {
            for (Claim c = s[hash & (s.length - 1)]; c != null; c = c.next) {
                if (c.hash == hash) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Lists the claim, and answers null if there is no other of its hash, the usual case, or the claim
     * itself if it has keys to compare with. But an open claim on the very key object takes no compare,
     * and no equal key can be running elsewhere: that one is joined and answered, and the claim stays out.
     * Running out of memory here leaves things as they were.
     */
    Claim stake(Claim mine) {
        synchronized (guard) {
            if (slots == null || count == slots.length) {
                grow();
            }
            int at = mine.hash & (slots.length - 1);
            Claim answer = null;
            for (Claim c = slots[at]; c != null; c = c.next) {
                if (c.hash == mine.hash) {
                    if (c.key == mine.key && c.open) {
                        c.users++;
                        return c;
                    }
                    answer = mine;
                }
            }
            mine.order = ++staked;
            mine.next = slots[at];
            slots[at] = mine;
            count++;
            return answer;
        }
    }

    private void grow() {
        Claim[] s = new Claim[slots == null ? 8 : slots.length << 1];
        for (int i = 0; slots != null && i < slots.length; i++) {
            Claim c = slots[i];
            while (c != null) {
                Claim after = c.next;
                int at = c.hash & (s.length - 1);
                c.next = s[at];
                s[at] = c;
                c = after;
            }
        }
        slots = s;
    }

    /**
     * Called after {@link #stake} from the same frame, and this frame is no larger and calls nothing,
     * so that having got that far, a thread out of stack can still leave, and no claim outlasts its op.
     */
    void leave(Claim c) {
        synchronized (guard) {
            if (--c.users > 0 || c.order == 0) {
                return;
            }
            int at = c.hash & (slots.length - 1);
            Claim before = slots[at];
            if (before == c) {
                slots[at] = c.next;
            } else {
                while (before.next != c) {
                    before = before.next;
                }
                before.next = c.next;
            }
            c.order = 0;
            count--;
        }
    }

    /**
     * The earlier claim on this key, which has taken this thread in place of {@code mine}, or null if
     * there is none. The thread's own come first: another's may hold only a thread still comparing,
     * which this thread may be holding up by a lock of its own.
     */
    Claim joined(Claim mine) {
        Claim c = joinFirstEqual(mine, true);
        return c != null ? c : joinFirstEqual(mine, false);
    }

    private Claim joinFirstEqual(Claim mine, boolean held) {
        Object key = mine.key;
        long at = mine.order;
        for (Claim c = before(mine.hash, at); c != null; c = before(mine.hash, at)) {
            at = c.order;
            if (Thread.holdsLock(c) == held && (key == c.key || key.equals(c.key)) && join(mine, c, held)) {
                return c;
            }
        }
        return null;
    }

    /** The latest claim of this hash staked before {@code order}. By number, as links change when the list grows. */
    private Claim before(int hash, long order) {
        synchronized (guard) {
            Claim best = null;
            for (Claim c = slots[hash & (slots.length - 1)]; c != null; c = c.next) {
                if (c.hash == hash && c.order < order && (best == null || c.order > best.order)) {
                    best = c;
                }
            }
            return best;
        }
    }

    /**
     * Fails if the other has ended since it was seen: what the compare said then no longer matters. A
     * claim its thread holds and has not opened means a key's {@code equals} is writing that key: that
     * is opened for it, which a claim given up forgets. Whoever stopped behind that one looks again.
     */
    private boolean join(Claim mine, Claim other, boolean held) {
        synchronized (guard) {
            if (other.order == 0) {
                return false;
            }
            // Out of the list now, though some may have stopped behind it. They find it not open.
            mine.users = 1;
            leave(mine);
            mine.open = false;
            other.users++;
            other.open |= held;
            return true;
        }
    }

    /** Waits out those someone runs in now. Its own thread's are entered at once. */
    void awaitRunning() {
        for (Claim c : running()) {
            if (c == null) {
                return;
            }
            synchronized (c) {
                // Having got in is the news.
            }
        }
    }

    private Claim[] running() {
        synchronized (guard) {
            Claim[] out = new Claim[count];
            int n = 0;
            for (int i = 0; slots != null && i < slots.length; i++) {
                for (Claim c = slots[i]; c != null; c = c.next) {
                    if (c.open) {
                        out[n++] = c;
                    }
                }
            }
            return out;
        }
    }
}
