// VMARGS: -Duser.timezone=UTC
package time;

import java.time.*;
import java.time.format.*;

/** LocalDateTime.parse(CharSequence): accepted shapes, round trips, error messages and indexes (docs/design/time.md, rules P1 to P4). */
public class LocalDateTimeParse {
    static void parse(String text) {
        String out;
        try {
            out = String.valueOf(LocalDateTime.parse(text));
        } catch (DateTimeParseException e) {
            out = e.getMessage() + " |idx=" + e.getErrorIndex() + " parsed=" + e.getParsedString() + " cause=" + (e.getCause() == null ? "null" : e.getCause().getClass().getName());
        } catch (Throwable e) {
            out = e.toString();
        }
        System.out.println("<" + text + ">: " + out);
    }

    public static void main(String[] args) {
        // P1: the shapes toString writes come back
        LocalDateTime[] samples = {LocalDateTime.of(2020, 1, 2, 3, 4), LocalDateTime.of(2020, 1, 2, 3, 4, 5), LocalDateTime.of(2020, 1, 2, 3, 4, 5, 120000000),
            LocalDateTime.of(2020, 1, 2, 3, 4, 5, 123456000), LocalDateTime.of(2020, 1, 2, 3, 4, 5, 123456789), LocalDateTime.of(2020, 1, 2, 3, 4, 0, 7),
            LocalDateTime.of(0, 1, 1, 0, 0), LocalDateTime.of(-5, 12, 31, 23, 59, 59), LocalDateTime.of(10000, 1, 1, 0, 0), LocalDateTime.of(-10000, 6, 6, 6, 6),
            LocalDateTime.of(999999999, 12, 31, 23, 59, 59, 999999999), LocalDateTime.of(-999999999, 1, 1, 0, 0), LocalDateTime.of(2020, 2, 29, 12, 0),
            LocalDateTime.of(1900, 2, 28, 12, 0), LocalDateTime.of(2000, 2, 29, 12, 0)};
        for (LocalDateTime s : samples) {
            System.out.println("round trip " + s + ": " + LocalDateTime.parse(s.toString()).equals(s));
        }
        // P2: other accepted spellings
        String[] ok = {"2020-01-02T03:04", "2020-01-02T03:04:05", "2020-01-02T03:04:05.", "2020-01-02T03:04:05.1", "2020-01-02T03:04:05.123456789", "2020-01-02t03:04",
            "2020-01-02T00:00", "2020-01-02T23:59:59.999999999", "+12345-01-02T03:04", "-12345-01-02T03:04", "-0001-01-02T03:04", "+02020-01-02T03:04", "0000-01-01T00:00",
            "2020-02-29T00:00", "2000-02-29T00:00", "-0004-02-29T00:00", "0000-02-29T00:00", "+999999999-12-31T23:59:59", "-999999999-01-01T00:00", "2020-12-31T23:59:60"};
        for (String s : ok) parse(s);
        // P3: text that does not match the grammar fails at the first offending index
        String[] syntax = {"", "x", "2020", "2020-01", "2020-01-02", "2020-01-02T", "2020-01-02T03", "2020-01-02T03:", "2020-01-02T3:04", "2020-01-02T03:4", "2020-1-02T03:04",
            "2020-01-2T03:04", "2020-01-02 03:04", "2020/01/02T03:04", "20200102T0304", "2020-01-02T03:04:5", "2020-01-02T03:04:05.1234567890", "2020-01-02T03:04:05.x",
            "2020-01-02T03:04:", "2020-01-02T03.04", " 2020-01-02T03:04", "-0000-01-01T00:00", "+0000-01-01T00:00", "+2020-01-01T00:00", "12345-01-01T00:00", "+123-01-01T00:00",
            "-1-01-01T00:00", "+1000000000-01-01T00:00", "-9999999999-01-01T00:00", "+99999999999-01-01T00:00", "٢020-01-02T03:04", "2020-01-02T03:04:05.١"};
        for (String s : syntax) parse(s);
        // P4: text after a complete date-time is unparsed, at its index
        for (String s : new String[] {"2020-01-02T03:04Z", "2020-01-02T03:04+01:00", "2020-01-02T03:04:05Z", "2020-01-02T03:04[UTC]", "2020-01-02T03:04 ", "2020-01-02T03:04:05.123x",
            "2020-01-02T03:04:05.123456789123", "2020-01-02T03:04:05..", "2020-01-02T03:04:05:06"}) {
            parse(s);
        }
        // P5: a field out of range, or a day that does not exist, is a resolution error at index 0 with the DateTimeException as cause
        for (String s : new String[] {"2020-13-01T00:00", "2020-00-01T00:00", "2020-01-32T00:00", "2020-01-00T00:00", "2020-02-30T00:00", "2021-02-29T00:00", "1900-02-29T00:00",
            "2020-04-31T00:00", "2020-06-31T00:00", "2020-09-31T00:00", "2020-11-31T00:00", "2020-01-02T25:00", "2020-01-02T24:00", "2020-01-02T24:00:00", "2020-01-02T24:01",
            "2020-01-02T03:60", "2020-01-02T03:04:61", "2020-01-02T03:04:60", "2020-01-02T99:99", "+1000000000-01-01T00:00", "-1000000000-01-01T00:00", "+1000000000-13-01T00:00",
            "2020-13-99T99:99", "2020-01-02T99:00:99", "2020-01-02T00:99:99", "2020-01-02T03:04:99"}) {
            parse(s);
        }
        // parse accepts any CharSequence
        p("StringBuilder", () -> LocalDateTime.parse(new StringBuilder("2020-01-02T03:04")));
        p("StringBuffer", () -> LocalDateTime.parse(new StringBuffer("2020-01-02T03:04:05")));
        p("null", () -> LocalDateTime.parse(null));
        // long text in a message is cut at 64 characters
        StringBuilder sb = new StringBuilder("2020-01-02T03:04");
        for (int i = 0; i < 70; i++) sb.append('x');
        parse(sb.toString());
        parse("2020-01-02T03:04" + xs(47));
        parse("2020-01-02T03:04" + xs(48));
        parse("2020-01-02T03:04" + xs(49));
    }

    static String xs(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append((char) 120);
        return b.toString();
    }

    interface Act { Object run() throws Throwable; }

    static void p(String l, Act a) {
        try {
            System.out.println(l + ": " + a.run());
        } catch (Throwable e) {
            System.out.println(l + ": " + e);
        }
    }
}
