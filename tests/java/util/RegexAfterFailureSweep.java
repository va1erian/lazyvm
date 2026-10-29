package util;

/** The second half of the fragment sweep of RegexAfterFailure (see RegexAfterFailure.sweep). */
public class RegexAfterFailureSweep {
    public static void main(String[] args) {
        RegexAfterFailure.sweep(RegexAfterFailure.FRAGMENTS.length / 2, RegexAfterFailure.FRAGMENTS.length);
        System.out.println("done");
    }
}
