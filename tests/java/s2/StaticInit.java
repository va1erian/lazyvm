package s2;

public class StaticInit {
    static class Base {
        static int baseField = log("Base.field init");
        static { log("Base.clinit"); }
        static void touch() { log("Base.touch"); }
    }

    static class Derived extends Base {
        static int derivedField = log("Derived.field init");
        static { log("Derived.clinit"); }
        static void dtouch() { log("Derived.dtouch"); }
    }

    static class Lazy {
        static final int CONST = 42;
        static final String SCONST = "const-string";
        static final int NONCONST = log("Lazy.NONCONST init") + 1;
        static int plain = 5;
        static { log("Lazy.clinit"); }
    }

    static class ByCall { static { log("ByCall.clinit"); } static void m() { log("ByCall.m"); } }
    static class ByWrite { static int v; static { log("ByWrite.clinit"); } }
    static class ByNew { int x; static { log("ByNew.clinit"); } ByNew() { log("ByNew()"); } }
    static class ByArray { static { log("ByArray.clinit"); } }
    static class ByClassLit { static { log("ByClassLit.clinit"); } }

    interface Iface {
        int VALUE = log("Iface.VALUE init");
        default void d() { }
    }
    static class Impl implements Iface { static { log("Impl.clinit"); } }
    interface NoDefault { int NV = log("NoDefault.NV init"); }
    static class Impl2 implements NoDefault { static { log("Impl2.clinit"); } }

    static class First { static int a = log("First.a"); static int b = Second.c + 1; static { log("First.clinit b=" + b); } }
    static class Second { static int c = log("Second.c") + 10; static { log("Second.clinit"); } }

    static class Fails {
        static int x = 1;
        static { log("Fails.clinit"); if (x == 1) throw new IllegalStateException("clinit failed"); }
        static void m() { }
    }

    static int log(String s) {
        System.out.println("  [" + s + "]");
        return s.length();
    }

    public static void main(String[] args) {
        System.out.println("main start");
        System.out.println("CONST " + Lazy.CONST + " " + Lazy.SCONST);
        System.out.println("constants did not init Lazy");
        System.out.println("plain " + Lazy.plain);
        System.out.println("NONCONST " + Lazy.NONCONST);
        System.out.println("-- subclass init");
        Derived.dtouch();
        Derived.touch();
        System.out.println("-- class literal does not init");
        Object cl = ByClassLit.class;
        System.out.println("class literal ok " + (cl != null));
        System.out.println("-- call");
        ByCall.m();
        ByCall.m();
        System.out.println("-- write");
        ByWrite.v = 3;
        System.out.println("v " + ByWrite.v);
        System.out.println("-- new");
        new ByNew();
        new ByNew();
        System.out.println("-- array does not init");
        ByArray[] arr = new ByArray[2];
        System.out.println("array length " + arr.length);
        System.out.println("-- interface");
        new Impl();
        System.out.println("Impl made");
        System.out.println("VALUE " + Iface.VALUE);
        new Impl2();
        System.out.println("Impl2 made, NV not read");
        System.out.println("NV " + NoDefault.NV);
        System.out.println("-- cycle");
        System.out.println("b " + First.b);
        System.out.println("-- failing initializer");
        try {
            Fails.m();
        } catch (ExceptionInInitializerError e) {
            System.out.println("EIIE cause " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage());
        }
        try {
            Fails.m();
        } catch (NoClassDefFoundError e) {
            System.out.println("NoClassDefFoundError on retry");
        }
        System.out.println("done");
    }
}
