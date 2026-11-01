import static java.nio.charset.StandardCharsets.UTF_8;

import lazy.net.Tcp;

/** A hand-written HTTP/1.0 GET over lazy.net: prints the status line and the body's size. */
public class TcpGet {
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "example.com";
        byte[] request = ("GET / HTTP/1.0\r\nHost: " + host + "\r\n\r\n").getBytes(UTF_8);
        Tcp.connect(host, 80)
           .thenCompose(c -> c.write(request)
                   .thenCompose(v -> c.readAll())
                   .thenCompose(reply -> c.close().thenApply(v -> reply)))
           .whenComplete((reply, error) -> {
               if (error != null) {
                   System.err.println("failed: " + error.getCause().getMessage());
                   return;
               }
               String text = new String(reply, UTF_8);
               System.out.println(text.substring(0, text.indexOf("\r\n")));
               System.out.println((text.length() - text.indexOf("\r\n\r\n") - 4) + " bytes of body");
           });
    }
}
