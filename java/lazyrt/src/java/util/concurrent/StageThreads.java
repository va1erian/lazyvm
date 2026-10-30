package java.util.concurrent;

/** The default executor when the common pool has fewer than two threads: a new thread per task. */
final class StageThreads implements Executor {
    @Override
    public void execute(Runnable task) {
        new Thread(task).start();
    }
}
