// VMARGS: -Xmx1m
package thread;

import java.util.ArrayList;
import java.util.List;

/** The smallest heap accepted is enough to start, run out and report it. */
public class OutOfMemorySmallestHeap {
    static void recurse() {
        recurse();
    }

    public static void main(String[] args) {
        System.out.println("started");
        try {
            recurse();
        } catch (StackOverflowError e) {
            System.out.println("stack overflow caught");
        }
        List<byte[]> list = new ArrayList<>();
        try {
            while (true) {
                list.add(new byte[1 << 10]);
            }
        } catch (OutOfMemoryError e) {
            list = null;
            System.out.println(e.getMessage());
        }
        String joined = "";
        for (int i = 0; i < 4; i++) {
            joined += i;
        }
        System.out.println(joined);
    }
}
