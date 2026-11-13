package lazy.msg;

import app.test.demo.v1.Demo;
import app.test.demo.v1.SumArgs;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/**
 * The shapes a generated client takes (docs/msg.md section 3): a reply of several fields as a
 * record with a list, an enum and an option; five arguments as a record with an unset option; a
 * method with no deadline of its own, bounded by within(); the caller a service reads.
 */
public class Shapes {
    public static void main(String[] args) throws Exception {
        Registration reg = Demo.serve(new Store());
        Demo demo = Demo.connect();
        demo.put("readme", "hello").toCompletableFuture().get();
        System.out.println(show(demo.stat("readme")));
        System.out.println(show(demo.stat("docs/notes")));
        System.out.println(show(demo.stat("docs/")));
        System.out.println(show(demo.sum(new SumArgs().a(1).b(2).c(3).d(4))));
        System.out.println(show(demo.sum(new SumArgs().a(1).b(2).c(3).d(4).e(1L << 40))));
        System.out.println(show(demo.wait_(20)));
        System.out.println(show(demo.within(20).wait_(2000)));
        System.out.println(show(demo.whoami()));
        System.out.println("poke: " + show(demo.poke("hi")));
        reg.close();
    }

    static String show(CompletionStage<?> stage) throws InterruptedException {
        try {
            return "ok " + stage.toCompletableFuture().get();
        } catch (ExecutionException e) {
            return e.getCause().getMessage();
        }
    }
}
