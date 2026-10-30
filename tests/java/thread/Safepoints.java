// TIMEOUT: 30
package thread;

/**
 * A thread that never blocks still lets the others run, whether it only makes
 * calls (no backward branch anywhere) or only loops (no call anywhere). If it
 * did not yield, main would never get to set the flag, and the worker would run
 * into the time limit.
 */
public class Safepoints {
    static volatile boolean stop;

    static int calls(int depth) {
        if (stop || depth == 0) {
            return 0;
        }
        return calls(depth - 1) + calls(depth - 1);
    }

    static int loop() {
        int x = 0;
        while (!stop) {
            x++;
        }
        return x;
    }

    static void stopWorker(String name, Thread t) throws Exception {
        stop = false;
        t.start();
        Thread.sleep(50);
        stop = true;
        t.join();
        System.out.println(name + " stopped");
    }

    public static void main(String[] args) throws Exception {
        stopWorker("calls", new Thread(() -> calls(60)));
        stopWorker("loop", new Thread(() -> loop()));
    }
}
