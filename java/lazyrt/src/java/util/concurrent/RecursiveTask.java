package java.util.concurrent;

public abstract class RecursiveTask<V> extends ForkJoinTask<V> {
    private V result;

    public RecursiveTask() {
    }

    protected abstract V compute();

    public final V getRawResult() {
        return result;
    }

    protected final void setRawResult(V value) {
        result = value;
    }

    protected final boolean exec() {
        result = compute();
        return true;
    }
}
