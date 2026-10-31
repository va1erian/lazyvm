package java.util.concurrent;

/**
 * The claims on a {@link HashCore}, found by hash, so that a write pays for those of its own hash and
 * not for how many exist. All of this is guarded by the core's monitor, except {@link #mayHold}, which
 * may also be read without it, as a hint: every link it follows leads on to an end, at any moment.
 *
 * <p>What holds:
 * <ul>
 * <li>Ops on equal keys never run together, except on one thread, and except for what a key's own
 * {@code equals} writes while it compares: that goes straight through, or it could wait for itself. A thread lists its claim before it
 * compares keys, with that claim's monitor held, and in the same step of the guard takes the list of
 * those of its hash already there, which is all it ever compares with. Of two overlapping ops the later
 * therefore sees the earlier's claim, and joins it instead of running. And so no number of later
 * arrivals, of any key, can make a comparing thread start over.
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
    /**
     * A lock on one key, listed for as long as anyone holds it or will. Who runs in it holds its monitor.
     * Its fields are not private, so that {@link #leave} takes no accessor call.
     */
    static final class Claim {
        final int hash;
        final Object key;
        boolean listed;
        Claim next;
        /** Who will still enter it, its first holder included. */
        int users = 1;
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
     * Lists the claim, and answers the claims of its hash that were already listed, or null if there are
     * none, the usual case. But an open claim on the very key object takes no compare, as it stands for
     * that key: that one is joined and answered alone, and {@code mine} is not listed.
     * Running out of memory here leaves things as they were.
     */
    Claim[] stake(Claim mine) {
        synchronized (guard) {
            if (slots == null || count == slots.length) {
                grow();
            }
            int at = mine.hash & (slots.length - 1);
            int same = 0;
            for (Claim c = slots[at]; c != null; c = c.next) {
                if (c.hash == mine.hash) {
                    if (c.key == mine.key && c.open) {
                        Claim[] only = {c};
                        c.users++;
                        return only;
                    }
                    same++;
                }
            }
            Claim[] earlier = same == 0 ? null : new Claim[same];
            for (Claim c = slots[at]; c != null && same > 0; c = c.next) {
                if (c.hash == mine.hash) {
                    earlier[--same] = c;
                }
            }
            mine.listed = true;
            mine.next = slots[at];
            slots[at] = mine;
            count++;
            return earlier;
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
     * The one call that must not run out of stack: called after {@link #stake} from the same frame, it
     * calls nothing and its frame is no larger, so that a thread that got as far as listing a claim can
     * still leave, and no claim outlasts its op. Only {@link #join} calls it from deeper, and if that
     * fails, the caller of {@link #joined} is still there to leave.
     *
     * <p>{@code givingUp} says that the first holder is going without having found out whether it may
     * run. Then the claim goes from the list now, in one step with closing it: if others could still
     * stop behind a claim that is closed and listed, they could keep it listed for good, each looking
     * again and stopping behind it before the last has left.
     */
    void leave(Claim c, boolean givingUp) {
        synchronized (guard) {
            if (givingUp) {
                c.open = false;
                c.users = 1;
            }
            if (--c.users > 0 || !c.listed) {
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
            c.listed = false;
            count--;
        }
    }

    /**
     * The earlier claim on this key, which has taken this thread in place of {@code mine}, or null if
     * there is none. The thread's own come first: another's may hold only a thread still comparing,
     * which this thread may be holding up by a lock of its own.
     */
    Claim joined(Claim mine, Claim[] earlier) {
        Claim c = joinFirstEqual(mine, earlier, true);
        return c != null ? c : joinFirstEqual(mine, earlier, false);
    }

    private Claim joinFirstEqual(Claim mine, Claim[] earlier, boolean held) {
        Object key = mine.key;
        for (Claim c : earlier) {
            if (Thread.holdsLock(c) == held && (key == c.key || key.equals(c.key)) && join(mine, c, held)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Fails if the other has ended since it was seen: what the compare said then no longer matters. A
     * claim its thread holds and has not opened means a key's {@code equals} is writing that key: that
     * is opened for it, which a claim given up forgets. Whoever stopped behind that one looks again.
     */
    private boolean join(Claim mine, Claim other, boolean held) {
        synchronized (guard) {
            if (!other.listed) {
                return false;
            }
            // Out of the list now, though some may have stopped behind it. They find it not open.
            leave(mine, true);
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
