package lazy.internal;

import java.util.Locale;
import java.util.Scanner;

/**
 * Library classes whose initializers use a pattern, first needed at the end of the stack, work from
 * then on: not enough stack is no syntax error, so they go back to not being initialized. A
 * hand-written golden: the oracle has its own classes that stay broken here.
 */
public class InitOverflow {
    static Throwable other;
    static boolean done;

    interface Use {
        void go();
    }

    static void down(Use use) {
        try {
            down(use);
        } catch (StackOverflowError e) {
            if (!done && other == null) {
                try {
                    use.go();
                    done = true;
                } catch (StackOverflowError again) {
                    // The next frame up has more.
                } catch (Throwable t) {
                    other = t;
                }
            }
            throw e;
        }
    }

    static void check(String name, Use use) {
        done = false;
        other = null;
        try {
            down(use);
        } catch (StackOverflowError e) {
            System.out.println(name + ", on the way back: " + other);
        }
        use.go();
        System.out.println(name + " works");
    }

    public static void main(String[] args) {
        check("case rules", () -> "i\u0307".toUpperCase(new Locale("lt")));
        check("Scanner", () -> new Scanner("12").nextInt());
    }
}
