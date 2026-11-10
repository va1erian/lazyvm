/**
 * The program tools/jdwp_smoke.py debugs. Each `// @name` comment marks a line
 * the test refers to, so edits here do not break it.
 */
public class JdwpTarget {
    static volatile int ticks;
    static int counter = 3;
    int field = 7;
    String label = "hello";
    int[] data = {10, 20, 30};
    long big = 1L << 40;
    boolean flag = true;
    double ratio = 2.5;

    /** A daemon that counts, so the test can see whether other threads stand still. */
    static final class Ticker implements Runnable {
        public void run() {
            while (true) {
                ticks++;
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    static int add(int a, int b) {
        int sum = a + b; // @add
        return sum; // @addret
    }

    int work(int n) {
        int total = 0; // @work
        for (int i = 0; i < n; i++) { // @loop
            total += add(i, field); // @call
        }
        return total; // @after
    }

    public static void main(String[] args) throws Exception {
        Thread ticker = new Thread(new Ticker(), "jdwp-ticker");
        ticker.setDaemon(true);
        ticker.start();
        JdwpTarget t = new JdwpTarget(); // @main
        int result = t.work(2); // @run
        System.out.println("result=" + result); // @print
        counter = result;
    }
}
