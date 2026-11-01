// NEEDS: lazyos-net
// ARGS: 10.0.2.2 47771
package lazy.net;

import java.util.concurrent.CompletionStage;

/** Inside LazyOS: 1 MiB through the host's echo server, by way of QEMU's gateway. */
public class HostEcho {
    static final int SIZE = 1024 * 1024;

    static long got, wrong;

    static CompletionStage<Void> drain(Connection c) {
        return c.read().thenCompose(bytes -> {
            for (byte b : bytes) {
                if (b != (byte) (got++ * 31)) wrong++;
            }
            return got >= SIZE || bytes.length == 0
                    ? c.close()
                    : drain(c);
        });
    }

    public static void main(String[] args) {
        byte[] data = new byte[SIZE];
        for (int i = 0; i < SIZE; i++) data[i] = (byte) (i * 31);
        Connection c = Tcp.connect(args[0], Integer.parseInt(args[1])).toCompletableFuture().join();
        System.out.println("peer " + c.peer().equals(args[0] + ":" + args[1]));
        c.write(data);
        drain(c).toCompletableFuture().join();
        System.out.println("echoed " + got + ", out of place " + wrong);
    }
}
