package thread;

/** A thread waiting for another thread's class initializer, as getState sees it. */
public class ClinitState {
    static volatile boolean release;

    static final class Gate {
        static int value;

        static {
            while (!release) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            value = 1;
        }
    }

    public static void main(String[] args) throws Exception {
        Thread init = new Thread(() -> System.out.println("initializer thread read " + Gate.value));
        init.start();
        Thread.sleep(100);
        final String[] seen = new String[1];
        Thread waiter = new Thread(() -> seen[0] = "waiter read " + Gate.value
                + ", interrupted " + Thread.currentThread().isInterrupted());
        waiter.start();
        Thread.sleep(100);
        System.out.println("waiting for <clinit>: " + waiter.getState());
        waiter.interrupt();
        Thread.sleep(50);
        System.out.println("after interrupt: " + waiter.getState());
        release = true;
        init.join();
        waiter.join();
        System.out.println(seen[0]);
    }
}
