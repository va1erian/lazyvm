package lazy.async;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import lazy.internal.Homes;
import lazy.internal.Reactor;

/**
 * The process's one event loop: its thread and its timers (docs/async.md). Every stage this API
 * returns completes on the loop's thread, which must never block. The loop keeps the VM running
 * while it has work pending.
 */
public final class Loop {
    private static final Executor EXECUTOR = Reactor::execute;

    private Loop() {}

    /** The loop's thread. Tasks run there one at a time, in the order they were given. */
    public static Executor executor() {
        return EXECUTOR;
    }

    /**
     * The executor whose thread is the current one: {@link #executor()} on the loop's thread, the UI
     * thread's executor there, and null on any other thread. Code that waits for a stage and must go
     * on where it started hands its continuation to this.
     */
    public static Executor home() {
        if (Reactor.onLoop()) return EXECUTOR;
        return Homes.of(Thread.currentThread());
    }

    /** Completes after at least this long, on a later turn of the loop even if that is zero. */
    public static CompletionStage<Void> delay(long time, TimeUnit unit) {
        if (unit == null) throw new NullPointerException("unit");
        return Reactor.delay(Math.max(0, unit.toNanos(time)));
    }
}
