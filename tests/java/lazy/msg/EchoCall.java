// NEEDS: msg
package lazy.msg;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import lazy.async.Loop;
import os.lazy.echo.v1.Echo;
import os.lazy.echo.v1.EchoServer;
import os.lazy.echo.v1.Event;

/**
 * Calls through a generated client to os.lazy.echo served in-process (docs/msg.md section 2): a
 * reply, a oneway, the IDL's deadline shortened with within(), a service that goes away while it
 * holds a call and comes back (retry=safe sends the call again), cancelling, and a name nobody
 * serves.
 */
public class EchoCall {
    static final CompletableFuture<Event> NOTIFIED = new CompletableFuture<>();

    static final class Service implements EchoServer {
        private final String tag;

        Service(String tag) {
            this.tag = tag;
        }

        @Override
        public CompletionStage<String> echo(String text, long count) {
            if (text.equals("hang") && tag.equals("one")) return new CompletableFuture<>();
            StringBuilder b = new StringBuilder(tag).append(':');
            for (long i = 0; i < count; i++) b.append(text);
            return CompletableFuture.completedFuture(b.toString());
        }

        @Override
        public CompletionStage<Boolean> ping() {
            return new CompletableFuture<>();
        }

        @Override
        public CompletionStage<Void> notify(Event event) {
            NOTIFIED.complete(event);
            return CompletableFuture.completedFuture(null);
        }
    }

    public static void main(String[] args) throws Exception {
        Registration first = Echo.serve(new Service("one"));
        System.out.println("served " + first.name());
        Echo echo = Echo.connect();
        System.out.println("echo: " + outcome(echo.echo("ab", 3)));
        System.out.println("notify: " + outcome(echo.notify(new Event().topic("saved").at(7))));
        System.out.println("received: " + NOTIFIED.get());

        System.out.println("ping within 100 ms: " + outcome(echo.within(100).ping()));

        CompletionStage<String> held = echo.echo("hang", 1);
        Registration[] second = new Registration[1];
        Loop.executor().execute(() -> {
            first.close();
            second[0] = Echo.serve(new Service("two"));
        });
        System.out.println("held, then a new service: " + outcome(held));

        CompletableFuture<Boolean> cancelled = echo.ping().toCompletableFuture();
        cancelled.cancel(true);
        System.out.println("cancelled: " + outcome(cancelled));

        System.out.println("nobody: " + outcome(Echo.connect("app.nobody.echo").ping()));
        try {
            Echo.serve(new Service("three"));
        } catch (java.io.UncheckedIOException e) {
            System.out.println("served twice: " + e.getCause().getMessage());
        }
        second[0].close();
        System.out.println("after close: " + outcome(echo.echo("x", 1)));
    }

    static String outcome(CompletionStage<?> stage) throws InterruptedException {
        try {
            return "ok " + stage.toCompletableFuture().get();
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            return c.getClass().getSimpleName() + ": " + c.getMessage();
        } catch (CancellationException e) {
            return "cancelled";
        }
    }
}
