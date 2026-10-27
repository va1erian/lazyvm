package java.util.regex;

/** The result of a match operation: the span of the match and of each capturing group. */
public interface MatchResult {
    int start();

    int start(int group);

    int end();

    int end(int group);

    String group();

    String group(int group);

    int groupCount();
}
