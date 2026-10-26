package s4;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.Arrays;

/** Runtime annotations: every element kind, defaults, equality, parameters and retention. */
public class Annotations {
    enum Level { LOW, @Mark("high") HIGH }

    @Retention(RetentionPolicy.RUNTIME) @interface Tag { String value(); }
    @Retention(RetentionPolicy.RUNTIME) @interface Mark { String value() default ""; }
    @Retention(RetentionPolicy.CLASS) @interface Hidden { }
    @interface NoRetention { }

    @Retention(RetentionPolicy.RUNTIME) @interface Kinds {
        byte b() default 1; short shortv() default 2; char c() default 'c'; int i() default 3;
        long l() default 4L; float f() default 5.5f; double d() default 6.5; boolean z() default true;
        String s() default "str"; Class<?> cls() default Object.class; Level level() default Level.LOW;
        Tag tag() default @Tag("dflt"); int[] ints() default {}; String[] strs() default {"a", "b"};
        Class<?>[] classes() default {int.class, String[].class}; Level[] levels() default {Level.HIGH};
        Tag[] tags() default {@Tag("x"), @Tag("y")}; double[] dd() default {1.5};
    }

    @Retention(RetentionPolicy.RUNTIME) @interface Explicit {
        int a(); String b(); Class<?> c(); Level d(); Tag e(); long[] f(); String[] g(); char[] h(); boolean[] i();
    }

    @Retention(RetentionPolicy.RUNTIME) @interface Prim {
        int i() default 1; String text() default "s"; long[] ls() default {1L, 2L}; double d() default 1.5;
        boolean z() default true; char ch() default 'x'; String[] ss() default {"a"};
    }

    @FunctionalInterface interface Fn { void f(); }

    @Kinds @Explicit(b = "x", a = 1, c = String.class, d = Level.HIGH, e = @Tag("t"), f = {1, 2}, g = {},
            h = {'p', 'q'}, i = {true, false})
    @Hidden @NoRetention @Mark("class") @Deprecated
    static class Target1 {
        @Mark("field") public int field;
        public int plain;
        @Mark("ctor") public Target1() { }
        @Mark("method") @Tag("m")
        public void method(@Mark("p0") int a, int b, @Tag("p2") @Mark("p2b") String c) { }
    }

    @Mark("class") static class Target2 { }
    @Mark("other") @Prim static class Target3 { }

    static class FakeTag implements Tag {
        public String value() { return "t"; }
        public Class<? extends Annotation> annotationType() { return Tag.class; }
    }

    public static void main(String[] args) throws Exception {
        Class<?> t = Target1.class;
        Annotation[] all = t.getAnnotations();
        System.out.println(all.length + " " + t.getDeclaredAnnotations().length);
        for (Annotation a : all) System.out.println(a.annotationType().getSimpleName());
        System.out.println(t.isAnnotationPresent(Kinds.class) + " " + t.isAnnotationPresent(Hidden.class) + " "
                + t.isAnnotationPresent(NoRetention.class) + " " + (t.getAnnotation(Hidden.class) == null));
        Kinds k = t.getAnnotation(Kinds.class);
        System.out.println(k.b() + " " + k.shortv() + " " + k.c() + " " + k.i() + " " + k.l() + " " + k.f() + " " + k.d() + " " + k.z());
        System.out.println(k.s() + " " + k.cls() + " " + k.level() + " " + k.tag().value() + " " + k.ints().length);
        System.out.println(Arrays.toString(k.strs()) + " " + Arrays.toString(k.classes()) + " " + Arrays.toString(k.levels())
                + " " + Arrays.toString(k.tags()) + " " + Arrays.toString(k.dd()));
        k.strs()[0] = "changed";
        System.out.println(k.strs()[0] + " " + (k.strs() != k.strs()));
        System.out.println(k);
        Explicit e = t.getAnnotation(Explicit.class);
        System.out.println(e.a() + e.b() + e.c().getName() + e.d() + e.e().value() + Arrays.toString(e.f()) + e.g().length
                + new String(e.h()) + Arrays.toString(e.i()));
        System.out.println(e);
        System.out.println(t.getAnnotation(Mark.class) + " " + t.getAnnotation(Deprecated.class));
        System.out.println(Fn.class.getAnnotation(FunctionalInterface.class) + " " + Fn.class.isAnnotationPresent(FunctionalInterface.class));
        System.out.println(e.annotationType() == Explicit.class);

        Mark m1 = t.getAnnotation(Mark.class), m2 = Target2.class.getAnnotation(Mark.class), m3 = Target3.class.getAnnotation(Mark.class);
        System.out.println(m1.equals(m2) + " " + m1.equals(m3) + " " + (m1.hashCode() == m2.hashCode()) + " " + m1.equals(null)
                + " " + m1.equals("x") + " " + m1.equals(k) + " " + m1.equals(m1) + " " + (m1 != m2));
        Tag real = e.e();
        System.out.println(real.equals(new FakeTag()) + " " + new FakeTag().equals(real));
        Prim prim = Target3.class.getAnnotation(Prim.class);
        System.out.println(m1.hashCode() + " " + real.hashCode() + " " + prim.hashCode() + " " + prim);
        System.out.println((e.hashCode() == t.getAnnotation(Explicit.class).hashCode()) + " " + (k.hashCode() == t.getAnnotation(Kinds.class).hashCode()));
        System.out.println(k.equals(t.getAnnotation(Kinds.class)) + " " + e.equals(k));

        Method method = t.getMethod("method", int.class, int.class, String.class);
        System.out.println(Arrays.toString(method.getAnnotations()) + " " + method.getDeclaredAnnotations().length);
        System.out.println(method.getAnnotation(Mark.class).value() + " " + method.getAnnotation(Tag.class).value() + " "
                + method.isAnnotationPresent(Hidden.class));
        Annotation[][] pa = method.getParameterAnnotations();
        System.out.println(pa.length + " " + Arrays.toString(pa[0]) + " " + Arrays.toString(pa[1]) + " " + Arrays.toString(pa[2]));
        Parameter[] ps = method.getParameters();
        System.out.println(ps[0].getAnnotation(Mark.class) + " " + ps[1].getAnnotations().length + " "
                + ps[2].getAnnotation(Tag.class) + " " + ps[2].isAnnotationPresent(Mark.class) + " " + ps[2].getDeclaredAnnotations().length);
        pa[0][0] = null;
        System.out.println(method.getParameterAnnotations()[0][0]);
        Constructor<?> ctor = t.getConstructor();
        System.out.println(ctor.getAnnotation(Mark.class) + " " + ctor.getParameterAnnotations().length);
        System.out.println(t.getField("field").getAnnotation(Mark.class) + " " + t.getField("plain").getAnnotations().length);
        System.out.println(Level.class.getField("HIGH").getAnnotation(Mark.class) + " " + Level.class.getField("LOW").getAnnotations().length);

        Method kd = Kinds.class.getMethod("d");
        System.out.println(kd.getDefaultValue() + " " + Kinds.class.getMethod("cls").getDefaultValue()
                + " " + Kinds.class.getMethod("tag").getDefaultValue() + " " + Tag.class.getMethod("value").getDefaultValue());
        System.out.println(Arrays.toString((String[]) Kinds.class.getMethod("strs").getDefaultValue())
                + " " + ((int[]) Kinds.class.getMethod("ints").getDefaultValue()).length
                + " " + Arrays.toString((Class<?>[]) Kinds.class.getMethod("classes").getDefaultValue()));
        System.out.println(Mark.class.getAnnotation(Retention.class).value());
        System.out.println(Mark.class.isAnnotation() + " " + Mark.class.isInterface() + " " + Mark.class.getInterfaces()[0].getName()
                + " " + Mark.class.getAnnotation(Retention.class));
        System.out.println(Target1.class.getAnnotation(Deprecated.class).annotationType().getName());
        System.out.println("done");
    }
}
