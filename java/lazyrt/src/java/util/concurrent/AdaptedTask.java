package java.util.concurrent;

/** Runs a callable or runnable as a task. A callable's checked failure goes in wrapped. */
final class AdaptedTask<T> extends ForkJoinTask<T> implements RunnableFuture<T> {
    static final int CALLABLE = 0;
    static final int RUNNABLE = 1;
    /** The {@code execute(Runnable)} form, whose failure escapes to the worker. */
    static final int EXECUTED = 2;

    private final Callable<? extends T> callable;
    private final int form;
    private T result;

    AdaptedTask(Callable<? extends T> callable, int form) {
        if (callable == null) {
            throw new NullPointerException();
        }
        this.callable = callable;
        this.form = form;
    }

    @Override
    public T getRawResult() {
        return result;
    }

    @Override
    protected void setRawResult(T value) {
        result = value;
    }

    @Override
    protected boolean exec() {
        try {
            result = callable.call();
            return true;
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            if (form == CALLABLE) {
                throw new RuntimeException(e);
            }
            ForkJoinTask.<RuntimeException>sneak(e);
            return false;
        }
    }

    @Override
    public void run() {
        invoke();
    }

    @Override
    boolean escapes() {
        return form == EXECUTED;
    }
}
