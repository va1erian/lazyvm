// VMARGS: -Xmx16m
package concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** A function fills the heap and throws, so its failure cannot be wrapped: the firing ends there. */
public class CompletableFutureNoHeap {
    static List<Object> hog;
    static final RuntimeException X = new RuntimeException("x");

    static void fillHeap() {
        hog = new ArrayList<>(100000);
        for (int n = 1 << 20; n >= 8; ) {
            try { hog.add(new byte[n]); } catch (OutOfMemoryError e) { n /= 2; }
        }
        try { for (;;) hog.add(new Object()); } catch (OutOfMemoryError e) { /* full */ }
    }

    static String trial(boolean fill) {
        int[] runs = new int[1];
        Throwable thrown = null, onVisit = null;
        CompletableFuture<String> s = new CompletableFuture<>();
        CompletableFuture<String> d = s.thenApply(v -> {
            runs[0]++;
            if (fill) fillHeap();
            throw X;
        });
        String value = "v", again = "w";
        try { s.complete(value); } catch (Throwable t) { thrown = t; }
        boolean doneAtOnce = d.isDone();
        hog = null;
        try { s.complete(again); } catch (Throwable t) { onVisit = t; }
        return "complete threw " + thrown + ", visit threw " + onVisit + ", done at once " + doneAtOnce
                + ", after a visit " + d.isDone() + ", runs " + runs[0] + ", dependents " + s.getNumberOfDependents();
    }

    public static void main(String[] args) {
        System.out.println("room: " + trial(false));
        for (int i = 0; i < 3; i++) System.out.println("no heap: " + trial(true));
    }
}
