/**
 * The second program tools/jdwp_smoke.py debugs: threads that have not started or
 * have ended, step filters when a step returns into excluded code, and a step request
 * left set on a thread that ends while another one starts. `// @name` marks lines.
 */
public class JdwpThreads {
    static Thread unstarted = new Thread("unstarted");
    static Thread finished;

    static final class Lib {
        static void each(Runnable r) {
            r.run();
            r.run();
            int z = 0;
            z++;
        }
    }

    static final class Cb implements Runnable {
        public void run() {
            int a = 1;
            a++; // @cb
        } // @cbend
    }

    static final class A extends Thread {
        A() {
            super("A");
        }

        public void run() {
            int a = 1; // @a
            a++;
        }
    }

    static final class B extends Thread {
        B() {
            super("B");
        }

        public void run() {
            int b = 1;
            b++;
            b++;
        }
    }

    public static void main(String[] args) throws Exception {
        finished = new Thread("finished");
        finished.start();
        finished.join();
        Lib.each(new Cb()); // @each
        int y = 2; // @after
        y++;
        Thread t = new A();
        t.start();
        t.join();
        Thread.sleep(200);
        Thread u = new B();
        u.start();
        u.join();
        System.out.println("threads done " + y);
    }
}
