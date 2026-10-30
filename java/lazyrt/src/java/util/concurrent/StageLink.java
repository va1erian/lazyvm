package java.util.concurrent;

import java.util.ArrayList;

/** An entry on a pending stage's list: a dependent step, or a thread blocked reading the stage. */
abstract class StageLink {
    /**
     * Called when one of the stages it is on has settled. Returns the stage this settled, whose own
     * list the caller then has to fire, or null.
     */
    abstract CompletableFuture<?> fire();

    /**
     * Fires a link and everything settled by it, on an explicit stack: a chain of stages must not
     * cost a frame per stage (docs/design/concurrent.md, "Order, counts, depth"). Lists of stages
     * settled here move onto the stack whole, last entry first, so they run first attached first
     * and each subtree ends before the next sibling.
     */
    static void fireTree(StageLink first) {
        ArrayList<StageLink> stack = null;
        StageLink link = first;
        while (link != null) {
            CompletableFuture<?> settled = link.fire();
            if (settled != null) {
                stack = settled.moveLinksOnto(stack);
            }
            link = stack == null || stack.isEmpty() ? null : stack.remove(stack.size() - 1);
        }
    }
}
