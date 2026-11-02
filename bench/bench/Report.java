package bench;

/** Prints the one result line tools/bench.py parses: a checksum and the elapsed nanoseconds. */
final class Report {
    private Report() {
    }

    /** Scale from the first argument (a repeat-count multiplier), default 1. */
    static int scale(String[] args) {
        return args.length > 0 ? Integer.parseInt(args[0]) : 1;
    }

    static void print(String name, long checksum, long startNanos) {
        long elapsed = System.nanoTime() - startNanos;
        System.out.println("BENCH " + name + " checksum=" + checksum + " ns=" + elapsed);
    }
}
