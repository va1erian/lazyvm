package util;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * When the VM exits, the handlers of every registered logger are closed; the handlers of an
 * anonymous logger are not (docs/design/logging.md, section 11). Only one handler of each kind is
 * used, because the order in which loggers are visited is not specified.
 */
public class LoggingExit {
    static class Announcing extends Handler {
        final String name;

        Announcing(String name) {
            this.name = name;
        }

        public void publish(LogRecord r) {
            System.out.println(name + " publish " + r.getMessage());
        }

        public void flush() {
            System.out.println(name + " flush");
        }

        public void close() {
            System.out.println(name + " close");
        }
    }

    public static void main(String[] args) {
        Logger named = Logger.getLogger("exit.named");
        named.setUseParentHandlers(false);
        named.addHandler(new Announcing("named"));
        Logger anonymous = Logger.getAnonymousLogger();
        anonymous.setUseParentHandlers(false);
        anonymous.addHandler(new Announcing("anonymous"));
        named.log(Level.INFO, "hello");
        anonymous.log(Level.INFO, "hello");
        System.out.println("end of main");
    }
}
