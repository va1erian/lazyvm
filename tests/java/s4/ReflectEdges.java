package s4;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.function.Supplier;

enum My$Enum { A }

/** Edge cases: failing class initializers, canonical names in messages, lambdas in annotation types. */
public class ReflectEdges {
    static class Boom {
        static int x = 1;
        static { if (x == 1) throw new RuntimeException("boom"); }
        static int f() { return 1; }
        Boom() { }
    }

    enum Inner { Q }

    @Retention(RetentionPolicy.RUNTIME) @interface WithLambda {
        Supplier<String> S = () -> "const";
        int v() default 1;
        String[] names() default {"a"};
        Class<?> c() default String.class;
    }

    @WithLambda(v = 5) static class User { }

    static void run(String label, Callable c) {
        try {
            System.out.println(label + " = " + c.call());
        } catch (Throwable e) {
            Throwable cause = e.getCause();
            System.out.println(label + " ! " + e.getClass().getName() + (cause == null ? "" : " / " + cause.getClass().getName())
                    + (e.getMessage() == null ? "" : " : " + e.getMessage()));
        }
    }

    interface Callable { Object call() throws Throwable; }

    public static void main(String[] args) throws Exception {
        Method f = Boom.class.getDeclaredMethod("f");
        run("first", () -> f.invoke(null));
        run("second", () -> f.invoke(null));
        run("new", () -> Boom.class.getDeclaredConstructor().newInstance());
        run("My$Enum", () -> Enum.valueOf(My$Enum.class, "Z"));
        run("Inner", () -> Enum.valueOf(Inner.class, "Z"));
        System.out.println(My$Enum.class.getCanonicalName() + " " + Inner.class.getCanonicalName() + " "
                + int[].class.getCanonicalName() + " " + Inner[].class.getCanonicalName());
        class Local { }
        System.out.println(Local.class.getCanonicalName() + " " + new Object() { }.getClass().getCanonicalName());
        WithLambda w = User.class.getAnnotation(WithLambda.class);
        System.out.println(w + " " + w.v() + " " + w.names().length + " " + w.c().getName());
        run("equals", () -> w.equals(User.class.getAnnotation(WithLambda.class)));
        run("equals self", () -> w.equals(w));
        System.out.println(WithLambda.S.get());
        System.out.println("done");
    }
}
