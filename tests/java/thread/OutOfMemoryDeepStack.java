// VMARGS: -Xmx8m -Xss128m
package thread;

/**
 * A stack with more frames than the heap could hold a trace of: a trace is short
 * however deep the stack, so the error can still be made, caught and printed.
 */
public class OutOfMemoryDeepStack {
    static int depth;

    static void recurse() {
        depth++;
        recurse();
    }

    public static void main(String[] args) {
        for (int i = 0; i < 3; i++) {
            depth = 0;
            try {
                recurse();
            } catch (StackOverflowError e) {
                System.out.println("caught, deeper than the heap: " + (depth > 8 << 20 >> 4)
                        + ", trace " + e.getStackTrace().length);
            }
        }
    }
}
