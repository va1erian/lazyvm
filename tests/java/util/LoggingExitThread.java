package util;

import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * A handler closed at VM exit may wait for a thread that uses the logging classes; the exit must not hang
 * on a lock held across the close (docs/design/logging.md, section 11).
 */
public class LoggingExitThread {
    public static void main(String[] args) {
        Logger named = Logger.getLogger("exit.thread");
        named.setUseParentHandlers(false);
        named.addHandler(new Handler() {
            public void publish(LogRecord r) {}

            public void flush() {}

            public void close() {
                Thread t = new Thread(() -> {
                    Logger made = Logger.getLogger("exit.thread.made");
                    System.out.println("thread sees " + LogManager.getLogManager().getLogger("exit.thread.made").getName()
                            + " " + (made != null));
                });
                t.start();
                try {
                    t.join();
                } catch (InterruptedException e) {
                    System.out.println("interrupted");
                }
                System.out.println("closed after the thread");
            }
        });
        System.out.println("end of main");
    }
}
