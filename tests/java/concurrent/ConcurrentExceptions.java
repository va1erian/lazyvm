package concurrent;

import java.util.concurrent.*;

/** The package's exception classes: each constructor's message and cause. */
public class ConcurrentExceptions {
    static void show(String label, Throwable t) {
        System.out.println(label + " = " + t.getClass().getSimpleName() + " message=" + t.getMessage()
            + " cause=" + t.getCause() + " text=" + t);
    }

    /** CompletionException's first two constructors are protected. */
    static class Completion extends CompletionException {
        Completion() { }
        Completion(String m) { super(m); }
    }

    /** So are ExecutionException's. */
    static class Execution extends ExecutionException {
        Execution() { }
        Execution(String m) { super(m); }
    }

    public static void main(String[] args) {
        Exception c = new IllegalStateException("c");
        show("Cancellation", new CancellationException());
        show("Cancellation msg", new CancellationException("m"));
        show("BrokenBarrier", new BrokenBarrierException());
        show("BrokenBarrier msg", new BrokenBarrierException("m"));
        show("Timeout", new TimeoutException());
        show("Timeout msg", new TimeoutException("m"));
        show("Rejected", new RejectedExecutionException());
        show("Rejected msg", new RejectedExecutionException("m"));
        show("Rejected msg cause", new RejectedExecutionException("m", c));
        show("Rejected cause", new RejectedExecutionException(c));
        show("Rejected null cause", new RejectedExecutionException((Throwable) null));
        show("Completion", new Completion());
        show("Completion msg", new Completion("m"));
        show("Completion msg cause", new CompletionException("m", c));
        show("Completion cause", new CompletionException(c));
        show("Completion null cause", new CompletionException((Throwable) null));
        show("Execution", new Execution());
        show("Execution msg", new Execution("m"));
        show("Execution msg cause", new ExecutionException("m", c));
        show("Execution cause", new ExecutionException(c));
        show("Execution null cause", new ExecutionException((Throwable) null));
        System.out.println("Cancellation is IllegalState = " + (new CancellationException() instanceof IllegalStateException));
        System.out.println("Completion is Runtime = " + ((Object) new Completion() instanceof RuntimeException));
        System.out.println("Rejected is Runtime = " + ((Object) new RejectedExecutionException() instanceof RuntimeException));
        System.out.println("Execution is checked = " + !((Object) new Execution() instanceof RuntimeException));
        System.out.println("Timeout is checked = " + !((Object) new TimeoutException() instanceof RuntimeException));
        System.out.println("BrokenBarrier is checked = " + !((Object) new BrokenBarrierException() instanceof RuntimeException));
    }
}
