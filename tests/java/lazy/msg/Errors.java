package lazy.msg;

import app.test.demo.v1.Demo;
import app.test.demo.v1.DemoCodec;
import app.test.demo.v1.DemoException;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import lazy.internal.msg.Broker;
import lazy.internal.msg.Fault;
import lazy.internal.msg.WireReader;

/**
 * How failures reach a client (docs/msg.md section 4): a declared error as the interface's own
 * exception, an errno, an exception the service did not mean to throw (EIO), a reply that does not
 * encode, an argument that does not encode, and requests no generated client would send.
 */
public class Errors {
    public static void main(String[] args) throws Exception {
        Registration reg = Demo.serve(new Store());
        Demo demo = Demo.connect();
        for (String key : new String[] {"missing", "busy", "locked", "boom", "null"}) {
            System.out.println(key + ": " + describe(demo.get(key)));
        }
        System.out.println("null key: " + describe(demo.get(null)));
        System.out.println("unknown method: " + raw(DemoCodec.INTERFACE_ID, 999_999, new byte[0]));
        System.out.println("wrong interface: " + raw(1L, DemoCodec.M_GET, new byte[0]));
        System.out.println("bad body: " + raw(DemoCodec.INTERFACE_ID, DemoCodec.M_GET, new byte[] {1, 2, 3}));
        reg.close();
    }

    static String describe(CompletionStage<?> stage) throws InterruptedException {
        try {
            return "ok " + stage.toCompletableFuture().get();
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            String text = c.getClass().getSimpleName() + " " + c.getMessage();
            if (c instanceof DemoException) text += " [" + ((DemoException) c).error() + "]";
            if (c instanceof MessengerException) {
                MessengerException m = (MessengerException) c;
                text += " code=" + m.code() + " errno=" + m.errno();
            }
            return text;
        }
    }

    /** A request sent below the generated client, answered with the standard error. */
    static String raw(long headerId, int method, byte[] body) throws Exception {
        CompletableFuture<String> answer = new CompletableFuture<>();
        Broker.get().call(new Broker.Outgoing("app.test.demo", headerId, method, body, Collections.emptyList(), false) {
            @Override
            public void answer(byte[] reply) {
                Fault f = WireReader.fault(reply);
                answer.complete(f == null ? "no error" : f.code + " " + f.sentence);
            }

            @Override
            public void fail(int errno, boolean delivered) {
                answer.complete("failed " + errno);
            }
        });
        return answer.get();
    }
}
