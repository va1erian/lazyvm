package lazy.internal.msg;

import java.util.concurrent.Executor;
import lazy.async.Loop;
import lazy.msg.Caller;

/** Per-thread state of the Messenger runtime: the caller being served, and where code goes home. */
public final class Calls {
    private static final ThreadLocal<Caller> CURRENT = new ThreadLocal<>();

    private Calls() {}

    /** The caller of the call dispatched on this thread, or null. */
    public static Caller current() {
        return CURRENT.get();
    }

    static void enter(Caller caller) {
        CURRENT.set(caller);
    }

    static void leave() {
        CURRENT.remove();
    }

    /** Where handlers made on this thread run: its home, else the loop. */
    static Executor home() {
        Executor home = Loop.home();
        return home != null ? home : Loop.executor();
    }
}
