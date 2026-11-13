package lazy.msg;

import app.test.demo.v1.DemoCodec;
import java.util.Arrays;
import java.util.Collections;
import lazy.internal.msg.Fault;
import lazy.internal.msg.TopicNames;
import lazy.internal.msg.Wire;
import lazy.internal.msg.WireReader;
import lazy.internal.msg.WireWriter;

/**
 * What the wire reader does with bodies no generated encoder writes (it parses another
 * process's bytes, so each is a refusal, never a crash), and the topic name rules LazyOS's broker
 * has.
 */
public class Hostile {
    public static void main(String[] args) {
        // A NUL inside an error's sentence would start the detail record: the writer cuts it.
        byte[] err = new WireWriter().error(13, "bad\0rest", "", null, null).toBytes();
        Fault f = WireReader.fault(err);
        System.out.println("NUL in sentence: " + f.code + " \"" + f.sentence + "\"");

        System.out.println("truncated header: " + refused(new byte[] {1, 1, 0}));
        System.out.println("length past the end: " + refused(new byte[] {7, 1, 0, 0, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff}));
        System.out.println("too deep: " + deep(100));
        System.out.println("nested 10: " + deep(10));

        // A field of an unknown kind is skipped; the id is the tag's low 16 bits past the kind.
        WireWriter w = new WireWriter();
        byte[] odd = {(byte) 0x63, 1, 0, 0, 1, 0, 0, 0, 42};
        w.string(1, "k");
        byte[] body = concat(odd, w.toBytes());
        System.out.println("unknown kind skipped: " + DemoCodec.readChange(new WireReader(body)));
        byte[] wide = {7, 1, 0, 1, 1, 0, 0, 0, 'x'};
        System.out.println("id is 16 bits: " + DemoCodec.readChange(new WireReader(wide)));
        System.out.println("wrong kind: " + refusedChange(new byte[] {2, 1, 0, 0, 4, 0, 0, 0, 1, 0, 0, 0}));
        System.out.println("buffer outside its pages: " + badBuffer());

        System.out.println("a/# matches a: " + TopicNames.matches("a/#", "a"));
        System.out.println("a/# matches a/b/c: " + TopicNames.matches("a/#", "a/b/c"));
        System.out.println("a/+ matches a: " + TopicNames.matches("a/+", "a"));
        for (String v : new String[] {"ok-1.x", "sp ace", "..", "a/b"}) {
            try {
                TopicNames.name("k/+", v);
                System.out.println("segment \"" + v + "\": accepted");
            } catch (IllegalArgumentException e) {
                System.out.println("segment \"" + v + "\": " + e.getMessage());
            }
        }
        System.out.println("tail a/b: " + TopicNames.name("k/#", "a/b"));
    }

    static String refused(byte[] body) {
        try {
            WireReader r = new WireReader(body);
            while (r.next()) { }
            return "accepted";
        } catch (Wire.Failure e) {
            return "errno " + e.errno + " " + e.getMessage();
        }
    }

    static String refusedChange(byte[] body) {
        try {
            return String.valueOf(DemoCodec.readChange(new WireReader(body)));
        } catch (Wire.Failure e) {
            return "errno " + e.errno + " " + e.getMessage();
        }
    }

    /** {@code levels} Option fields, each holding the next. */
    static String deep(int levels) {
        WireWriter w = new WireWriter();
        int[] marks = new int[levels];
        for (int i = 0; i < levels; i++) marks[i] = w.begin(Wire.OPTION, 1);
        for (int i = levels - 1; i >= 0; i--) w.end(marks[i]);
        try {
            WireReader r = new WireReader(w.toBytes());
            int depth = 0;
            while (r.next()) {
                r = r.open(Wire.OPTION);
                depth++;
            }
            return "read " + depth;
        } catch (Wire.Failure e) {
            return "errno " + e.errno + " " + e.getMessage();
        }
    }

    static String badBuffer() {
        WireWriter w = new WireWriter();
        Buffer small = Buffer.allocate(16);
        w.buffer(1, small.range(0, 4));
        byte[] body = w.toBytes();
        body[16] = (byte) 0xff;  // the length, now far past the 16 bytes of pages
        WireReader r = new WireReader(body, Collections.singletonList(small));
        r.next();
        try {
            return "accepted " + r.buffer().length();
        } catch (Wire.Failure e) {
            return "errno " + e.errno;
        }
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
