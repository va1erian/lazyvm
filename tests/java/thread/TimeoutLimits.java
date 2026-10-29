package thread;

/** Timeouts at the top of the long range neither overflow nor return early. */
public class TimeoutLimits {
    public static void main(String[] args) throws Exception {
        final Thread worker = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                System.out.println("unexpected interrupt");
            }
        });
        worker.start();
        worker.join(Long.MAX_VALUE);
        System.out.println("join(MAX) waited for the end: " + !worker.isAlive());
        Thread worker2 = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                System.out.println("unexpected interrupt");
            }
        });
        worker2.start();
        try {
            // Java 8 rounds MAX up into a negative number.
            worker2.join(Long.MAX_VALUE, 999999);
            System.out.println("join(MAX, nanos) returned");
        } catch (IllegalArgumentException e) {
            System.out.println("join(MAX, nanos): " + e.getMessage());
        }
        worker2.join();
        final String[] out = new String[1];
        Thread sleeper = new Thread(() -> {
            try {
                Thread.sleep(Long.MAX_VALUE, 600000);
                out[0] = "woke up";
            } catch (InterruptedException e) {
                out[0] = "sleep(MAX, nanos) interrupted: " + e.getMessage();
            } catch (IllegalArgumentException e) {
                out[0] = "sleep(MAX, nanos): " + e;
            }
        });
        sleeper.start();
        // Java 8 rounds MAX up into a negative number here too.
        while (sleeper.getState() != Thread.State.TIMED_WAITING
                && sleeper.getState() != Thread.State.TERMINATED) {
            Thread.yield();
        }
        sleeper.interrupt();
        sleeper.join();
        System.out.println(out[0]);
        final Object o = new Object();
        Thread waiter = new Thread(() -> {
            synchronized (o) {
                try {
                    o.wait(Long.MAX_VALUE);
                    out[0] = "wait(MAX) notified";
                } catch (InterruptedException e) {
                    out[0] = "interrupted";
                }
            }
        });
        waiter.start();
        while (waiter.getState() != Thread.State.TIMED_WAITING) {
            Thread.yield();
        }
        synchronized (o) {
            o.notify();
        }
        waiter.join();
        System.out.println(out[0]);
    }
}
