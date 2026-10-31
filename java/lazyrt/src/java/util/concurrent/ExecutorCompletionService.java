package java.util.concurrent;

/**
 * Each task runs inside a wrapper whose completion queues the task. Queueing from the wrapper, not
 * the task, keeps a cancelled task out of the queue until its turn on the executor comes.
 */
public class ExecutorCompletionService<V> implements CompletionService<V> {
    private final Executor executor;
    private final AbstractExecutorService makesTasks;
    private final BlockingQueue<Future<V>> completed;

    public ExecutorCompletionService(Executor executor) {
        this(executor, new LinkedBlockingQueue<Future<V>>());
    }

    public ExecutorCompletionService(Executor executor, BlockingQueue<Future<V>> completionQueue) {
        if (executor == null || completionQueue == null) {
            throw new NullPointerException();
        }
        this.executor = executor;
        this.makesTasks = executor instanceof AbstractExecutorService ? (AbstractExecutorService) executor : null;
        this.completed = completionQueue;
    }

    private final class Delivering extends FutureTask<Void> {
        private final Future<V> task;

        Delivering(RunnableFuture<V> task) {
            super(task, null);
            this.task = task;
        }

        @Override
        protected void done() {
            completed.add(task);
        }
    }

    private Future<V> start(RunnableFuture<V> task) {
        executor.execute(new Delivering(task));
        return task;
    }

    public Future<V> submit(Callable<V> task) {
        if (task == null) {
            throw new NullPointerException();
        }
        return start(makesTasks != null ? makesTasks.newTaskFor(task) : new FutureTask<V>(task));
    }

    public Future<V> submit(Runnable task, V result) {
        if (task == null) {
            throw new NullPointerException();
        }
        return start(makesTasks != null ? makesTasks.newTaskFor(task, result) : new FutureTask<V>(task, result));
    }

    public Future<V> take() throws InterruptedException {
        return completed.take();
    }

    public Future<V> poll() {
        return completed.poll();
    }

    public Future<V> poll(long timeout, TimeUnit unit) throws InterruptedException {
        return completed.poll(timeout, unit);
    }
}
