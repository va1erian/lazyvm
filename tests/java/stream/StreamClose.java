package stream;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/** onClose handlers: order, repetition, and what happens when they throw. */
public class StreamClose {
    static StringBuilder log = new StringBuilder();
    static Runnable h(String tag) { return () -> log.append(tag).append(' '); }
    static Runnable boom(String tag, RuntimeException e) { return () -> { log.append(tag).append(' '); throw e; }; }
    static void show(String label) { System.out.println(label + ": " + log.toString().trim()); log.setLength(0); }
    static void thrown(String label, Throwable e) {
        StringBuilder sb = new StringBuilder(label + ": " + e);
        for (Throwable s : e.getSuppressed()) sb.append(" +").append(s);
        System.out.println(sb);
    }

    public static void main(String[] args) {
        // handlers run on close(), in registration order, not at the end of a terminal operation
        Stream<Integer> s = Stream.of(1, 2).onClose(h("a")).onClose(h("b")).onClose(h("c"));
        s.count();
        show("after count");
        s.close();
        show("after close");
        s.close();
        show("second close");

        // handlers registered on later stages count as well, and any stage closes them all
        Stream<Integer> base = Stream.of(1, 2).onClose(h("base"));
        Stream<Integer> mapped = base.map(x -> x).onClose(h("mapped"));
        Stream<Integer> last = mapped.filter(x -> true).onClose(h("last"));
        last.close();
        show("close last");
        Stream<Integer> base2 = Stream.of(1, 2).onClose(h("base"));
        Stream<Integer> mapped2 = base2.map(x -> x).onClose(h("mapped"));
        mapped2.close();
        show("close middle");
        Stream<Integer> base3 = Stream.of(1, 2).onClose(h("x"));
        Stream<Integer> mapped3 = base3.map(x -> x);
        System.out.println("linked source closes: " + tryClose(base3));
        show("close linked source");
        mapped3.close();
        show("close after source closed");

        // a consumed stream still takes handlers and runs them
        Stream<Integer> used = Stream.of(1);
        used.count();
        used.onClose(h("late"));
        used.close();
        show("late handler");

        // the terminal operation does not run them even when it throws
        Stream<Integer> thr = Stream.of(1).onClose(h("t"));
        try { thr.map(x -> { throw new IllegalStateException("e"); }).count(); } catch (IllegalStateException e) { System.out.println("caught " + e.getMessage()); }
        show("after throw");

        // try-with-resources
        try (Stream<Integer> r = Stream.of(1, 2).onClose(h("twr"))) {
            log.append(r.count()).append(' ');
        }
        show("try-with-resources");
        try (Stream<Integer> r = Stream.of(1, 2).onClose(h("twr"))) {
            r.map(x -> { throw new RuntimeException("body"); }).count();
        } catch (RuntimeException e) { thrown("twr body threw", e); }
        show("twr after throw");

        // exceptions from handlers: all handlers run, the first exception is thrown
        Stream<Integer> two = Stream.of(1).onClose(boom("a", new RuntimeException("e1"))).onClose(h("b")).onClose(boom("c", new IllegalStateException("e2"))).onClose(boom("d", new IllegalArgumentException("e3")));
        try { two.close(); } catch (RuntimeException e) { thrown("first wins", e); }
        show("all ran");
        try { two.close(); System.out.println("second close quiet"); } catch (RuntimeException e) { thrown("second close", e); }
        show("not rerun");

        // the same object thrown twice is not suppressed into itself
        RuntimeException same = new RuntimeException("same");
        Stream<Integer> dup = Stream.of(1).onClose(boom("a", same)).onClose(boom("b", same));
        try { dup.close(); } catch (RuntimeException e) { thrown("same twice", e); }
        show("same ran");
        // an exception that already has suppressed ones
        RuntimeException withSup = new RuntimeException("withSup");
        withSup.addSuppressed(new Exception("pre"));
        Stream<Integer> sup = Stream.of(1).onClose(boom("a", withSup)).onClose(boom("b", new IllegalArgumentException("e3b")));
        try { sup.close(); } catch (RuntimeException e) { thrown("pre-suppressed", e); }
        show("sup ran");
        // an Error from a handler wins over later exceptions
        Stream<Integer> err = Stream.of(1).onClose(() -> { log.append("a "); throw new StackOverflowError("so"); }).onClose(boom("b", new IllegalStateException("e2")));
        try { err.close(); } catch (Throwable e) { thrown("error first", e); }
        show("err ran");
        Stream<Integer> err2 = Stream.of(1).onClose(boom("a", new IllegalStateException("e2"))).onClose(() -> { log.append("b "); throw new AssertionError("late"); });
        try { err2.close(); } catch (Throwable e) { thrown("error second", e); }
        show("err2 ran");

        // concat closes both inputs
        Stream<Integer> cc = Stream.concat(Stream.of(1).onClose(h("c1")), Stream.of(2).onClose(h("c2"))).onClose(h("outer"));
        cc.close();
        show("concat close");
        Stream<Integer> cx = Stream.concat(Stream.of(1).onClose(boom("c1", new RuntimeException("e1"))), Stream.of(2).onClose(boom("c2", new IllegalStateException("e2"))));
        try { cx.close(); } catch (RuntimeException e) { thrown("concat throws", e); }
        show("concat ran");
        Stream<Integer> cy = Stream.concat(Stream.of(1).onClose(h("c1")), Stream.of(2).onClose(h("c2")));
        cy.count();
        show("concat count");

        // flatMap closes each inner stream after its content
        Stream<Integer> fm = Stream.of(1, 2).onClose(h("outer")).flatMap(x -> Stream.of(x).onClose(h("in" + x)));
        fm.forEach(x -> log.append("v" + x + " "));
        show("flatMap run");
        fm.close();
        show("flatMap close");
        Stream<Integer> fmThrow = Stream.of(1, 2).flatMap(x -> Stream.of(x).onClose(boom("in" + x, new RuntimeException("c" + x))));
        try { fmThrow.forEach(x -> log.append("v" + x + " ")); } catch (RuntimeException e) { thrown("flatMap inner close throws", e); }
        show("flatMap inner ran");

        // the primitive streams and the sources that create streams
        IntStream is = IntStream.range(0, 3).onClose(h("int"));
        is.sum(); is.close();
        show("IntStream");
        LongStream ls = LongStream.range(0, 3).onClose(h("long"));
        ls.close();
        show("LongStream");
        DoubleStream ds = DoubleStream.of(1).onClose(h("double"));
        ds.close();
        show("DoubleStream");
        Stream<Integer> mapToObj = IntStream.range(0, 2).onClose(h("i")).boxed().onClose(h("b"));
        mapToObj.close();
        show("boxed");
        Stream<Integer> mti = Stream.of("a").onClose(h("s")).mapToInt(String::length).onClose(h("m")).boxed();
        mti.close();
        show("mapToInt");
        Stream<Integer> lst = Arrays.asList(1, 2).stream().onClose(h("list"));
        lst.close();
        show("list.stream");
        Stream<Integer> par = Stream.of(1).parallel().onClose(h("par")).sequential();
        par.close();
        show("parallel/sequential");
        Stream<Integer> un = Stream.of(1).onClose(h("un")).unordered();
        un.close();
        show("unordered");
        Stream<Integer> sorted = Stream.of(2, 1).onClose(h("so")).sorted().limit(1).skip(0).distinct().peek(x -> {});
        sorted.close();
        show("chain");
        Stream<Integer> support = StreamSupport.stream(Arrays.asList(1).spliterator(), false).onClose(h("ss"));
        support.close();
        show("StreamSupport");
        Stream<Integer> empty = Stream.<Integer>empty().onClose(h("em"));
        empty.close();
        show("empty");
        Stream<Integer> built = Stream.<Integer>builder().add(1).build().onClose(h("bu"));
        built.close();
        show("builder");
        Stream<Integer> gen = Stream.generate(() -> 1).onClose(h("gen"));
        gen.close();
        show("generate");
        // handlers that close another stream
        Stream<Integer> inner = Stream.of(1).onClose(h("inner"));
        Stream<Integer> outer = Stream.of(1).onClose(inner::close).onClose(h("outer"));
        outer.close();
        show("nested close");
        // a handler that closes its own stream is not rerun
        Stream<Integer>[] self = new Stream[1];
        self[0] = Stream.of(1).onClose(() -> { log.append("self "); self[0].close(); });
        self[0].close();
        show("self close");
    }

    static String tryClose(Stream<?> s) {
        try { s.close(); return "ok"; } catch (RuntimeException e) { return e.toString(); }
    }
}
