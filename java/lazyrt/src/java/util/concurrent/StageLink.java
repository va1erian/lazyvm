package java.util.concurrent;

/**
 * An entry on a pending stage's list: a dependent step, or a thread blocked reading the stage. The
 * list is linked through its entries, so that one can leave from any place at no cost: an
 * {@code *Either} step leaves the other source's list, in whatever order the sources settle. All
 * of this is under {@link CompletableFuture#LOCK}, and an entry is on one list at a time.
 */
abstract class StageLink {
    /** The stage whose list this is on: not always a source, as a firing moves lists. */
    private CompletableFuture<?> list;
    private StageLink older;
    private StageLink newer;

    /**
     * Called when one of the stages it is on has settled. Returns the stage whose list the caller
     * has to go on with, as this settled it or found it settled, or null.
     */
    abstract CompletableFuture<?> fire();

    final void addTo(CompletableFuture<?> stage) {
        list = stage;
        older = stage.newestLink;
        if (older != null) {
            older.newer = this;
        }
        stage.newestLink = this;
        stage.linkCount++;
    }

    /** Leaves the list of {@code stage}; false if this is not on it. */
    final boolean removeFrom(CompletableFuture<?> stage) {
        if (list != stage) {
            return false;
        }
        if (older != null) {
            older.newer = newer;
        }
        if (newer != null) {
            newer.older = older;
        } else {
            stage.newestLink = older;
        }
        stage.linkCount--;
        list = null;
        older = null;
        newer = null;
        return true;
    }
}
