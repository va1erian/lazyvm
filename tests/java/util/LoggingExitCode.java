package util;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** System.exit also closes the handlers of registered loggers (docs/design/logging.md, section 11). */
public class LoggingExitCode {
    public static void main(String[] args) {
        Logger named = Logger.getLogger("exit.code");
        named.setUseParentHandlers(false);
        named.addHandler(new Handler() {
            public void publish(LogRecord r) {
                System.out.println("publish " + r.getMessage());
            }

            public void flush() {
                System.out.println("flush");
            }

            public void close() {
                System.out.println("close");
            }
        });
        named.log(Level.INFO, "hello");
        System.out.println("exiting with 2");
        System.exit(2);
    }
}
