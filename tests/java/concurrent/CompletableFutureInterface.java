package concurrent;

import java.util.concurrent.*;

/** Every CompletionStage method called through the interface type: a call goes through the bridge methods. */
public class CompletableFutureInterface {
    interface Stage { CompletionStage<?> make() throws Throwable; }

    static final Executor DIRECT = Runnable::run;

    static void show(String label, Stage stage) {
        try {
            CompletionStage<?> st = stage.make();
            CompletableFuture<?> f = st.toCompletableFuture();
            System.out.println(label + " = " + f.join() + " " + (st == f) + " " + f.getClass().getName());
        } catch (Throwable t) {
            System.out.println(label + " ! " + t);
        }
    }

    public static void main(String[] args) {
        CompletionStage<String> s = CompletableFuture.completedFuture("v");
        CompletionStage<String> o = CompletableFuture.completedFuture("o");
        CompletableFuture<String> failedOne = new CompletableFuture<>();
        failedOne.completeExceptionally(new IllegalStateException("x"));
        CompletionStage<String> failed = failedOne;

        show("thenApply", () -> s.thenApply(v -> v + 1));
        show("thenApplyAsync", () -> s.thenApplyAsync(v -> v + 2));
        show("thenApplyAsync e", () -> s.thenApplyAsync(v -> v + 3, DIRECT));
        show("thenAccept", () -> s.thenAccept(v -> { }));
        show("thenAcceptAsync", () -> s.thenAcceptAsync(v -> { }));
        show("thenAcceptAsync e", () -> s.thenAcceptAsync(v -> { }, DIRECT));
        show("thenRun", () -> s.thenRun(() -> { }));
        show("thenRunAsync", () -> s.thenRunAsync(() -> { }));
        show("thenRunAsync e", () -> s.thenRunAsync(() -> { }, DIRECT));
        show("thenCombine", () -> s.thenCombine(o, (a, b) -> a + b));
        show("thenCombineAsync", () -> s.thenCombineAsync(o, (a, b) -> a + b + 2));
        show("thenCombineAsync e", () -> s.thenCombineAsync(o, (a, b) -> a + b + 3, DIRECT));
        show("thenAcceptBoth", () -> s.thenAcceptBoth(o, (a, b) -> { }));
        show("thenAcceptBothAsync", () -> s.thenAcceptBothAsync(o, (a, b) -> { }));
        show("thenAcceptBothAsync e", () -> s.thenAcceptBothAsync(o, (a, b) -> { }, DIRECT));
        show("runAfterBoth", () -> s.runAfterBoth(o, () -> { }));
        show("runAfterBothAsync", () -> s.runAfterBothAsync(o, () -> { }));
        show("runAfterBothAsync e", () -> s.runAfterBothAsync(o, () -> { }, DIRECT));
        show("applyToEither", () -> s.applyToEither(o, a -> a + 1));
        show("applyToEitherAsync", () -> s.applyToEitherAsync(o, a -> a + 2));
        show("applyToEitherAsync e", () -> s.applyToEitherAsync(o, a -> a + 3, DIRECT));
        show("acceptEither", () -> s.acceptEither(o, a -> { }));
        show("acceptEitherAsync", () -> s.acceptEitherAsync(o, a -> { }));
        show("acceptEitherAsync e", () -> s.acceptEitherAsync(o, a -> { }, DIRECT));
        show("runAfterEither", () -> s.runAfterEither(o, () -> { }));
        show("runAfterEitherAsync", () -> s.runAfterEitherAsync(o, () -> { }));
        show("runAfterEitherAsync e", () -> s.runAfterEitherAsync(o, () -> { }, DIRECT));
        show("thenCompose", () -> s.thenCompose(v -> o));
        show("thenComposeAsync", () -> s.thenComposeAsync(v -> o));
        show("thenComposeAsync e", () -> s.thenComposeAsync(v -> o, DIRECT));
        show("whenComplete", () -> s.whenComplete((v, x) -> { }));
        show("whenCompleteAsync", () -> s.whenCompleteAsync((v, x) -> { }));
        show("whenCompleteAsync e", () -> s.whenCompleteAsync((v, x) -> { }, DIRECT));
        show("handle", () -> s.handle((v, x) -> v + x));
        show("handleAsync", () -> s.handleAsync((v, x) -> v + x + 2));
        show("handleAsync e", () -> s.handleAsync((v, x) -> v + x + 3, DIRECT));
        show("exceptionally", () -> failed.exceptionally(x -> "got " + x));
        show("failed through handle", () -> failed.handle((v, x) -> v + " " + x));
        show("toCompletableFuture", () -> s.toCompletableFuture());
    }
}
