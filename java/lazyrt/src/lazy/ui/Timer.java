package lazy.ui;

/**
 * A task {@link Ui#after} or {@link Ui#every} runs on the UI thread. Headless, timers run on the
 * test driver's clock (docs/ui.md, "Evidence and tests").
 */
public final class Timer extends Node {
    private final Runnable task;
    private final boolean repeat;
    private final Window window;

    private Timer(Runnable task, boolean repeat, Window window) {
        this.task = task;
        this.repeat = repeat;
        this.window = window;
    }

    static Timer start(int millis, Runnable task, boolean repeat) {
        Ui.check();
        Ui.nonNull(task, "task");
        if (millis <= 0) throw new IllegalArgumentException("period " + millis);
        if (!Ui.running) throw new IllegalStateException("timers start once Ui.run has opened the window");
        Timer t = new Timer(task, repeat, Ui.main);
        Ui.register(t);
        try {
            Ui.timer0(t.window.id, t.id, millis);
        } catch (RuntimeException e) {
            Ui.free(t);
            throw e;
        }
        t.window.owned.add(t);
        return t;
    }

    /** Whether the timer will still run its task. */
    public boolean active() {
        return mounted();
    }

    /** Stops the timer; its task does not run again. */
    public void cancel() {
        if (!mounted()) return;
        Ui.check();
        int was = id;
        Ui.free(this);
        window.owned.remove(this);
        Ui.forget0(new int[] {was});
    }

    @Override
    void fire(int kind, int i, double d, String s) {
        if (kind != Ui.TIMER) return;
        if (!repeat) cancel();
        task.run();
    }
}
