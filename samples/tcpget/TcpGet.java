import static java.nio.charset.StandardCharsets.UTF_8;

import lazy.net.Tcp;

/** A hand-written HTTP/1.0 GET over lazy.net: prints the status line and the body's size. */
public class TcpGet {
    /** Where {@code \r\n\r\n} starts in {@code reply}, or -1. */
    static int endOfHeaders(byte[] reply) {
        for (int i = 0; i + 3 < reply.length; i++) {
            if (reply[i] == '\r' && reply[i + 1] == '\n' && reply[i + 2] == '\r' && reply[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

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
               int end = endOfHeaders(reply);
               if (end < 0) {
                   System.err.println("failed: no end of headers in " + reply.length + " bytes");
                   return;
               }
               String headers = new String(reply, 0, end, UTF_8);
               int line = headers.indexOf("\r\n");
               System.out.println(line < 0 ? headers : headers.substring(0, line));
               System.out.println((reply.length - end - 4) + " bytes of body");
           });
    }
}
