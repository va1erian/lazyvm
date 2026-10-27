package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A region that ends between the halves of a surrogate pair: the pair is read whole, and $, \Z and
 * (?m)$ hold right after it (not \z) when the bounds anchor.
 */
public class RegexPairEnd {
    public static void main(String[] args) {
        String text = "abII😀";
        String body = "[\\x{1f600}I]*";
        String[] ends = {"$", "\\z", "\\Z", "(?m)$", "\\b", "\\B", "(?=\\z)", "(?!.)", "(?s)(?!.)", "(?<=I)"};
        int[][] regions = {{1, 5}, {1, 4}, {1, 6}, {2, 5}};
        for (String end : ends) {
            for (boolean anchoring : new boolean[] {true, false}) {
                for (boolean transparent : new boolean[] {false, true}) {
                    for (int[] r : regions) {
                        Pattern p = Pattern.compile(body + end);
                        Matcher m = p.matcher(text).region(r[0], r[1]);
                        m.useAnchoringBounds(anchoring);
                        m.useTransparentBounds(transparent);
                        StringBuilder sb = new StringBuilder(end + " anchoring=" + anchoring + " transparent=" + transparent + " region=" + r[0] + "," + r[1] + ":");
                        while (m.find()) {
                            sb.append(" [").append(m.start()).append(',').append(m.end()).append(']');
                        }
                        sb.append(" hitEnd=").append(m.hitEnd()).append(" requireEnd=").append(m.requireEnd());
                        Matcher n = p.matcher(text).region(r[0], r[1]);
                        n.useAnchoringBounds(anchoring);
                        n.useTransparentBounds(transparent);
                        sb.append(" lookingAt=").append(n.lookingAt() ? n.end() : -1);
                        n = p.matcher(text).region(r[0], r[1]);
                        n.useAnchoringBounds(anchoring);
                        n.useTransparentBounds(transparent);
                        sb.append(" matches=").append(n.matches());
                        System.out.println(sb);
                    }
                }
            }
        }
        System.out.println("done");
    }
}
