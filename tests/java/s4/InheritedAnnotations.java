package s4;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.Arrays;

/** @Inherited through superclasses only, and @Repeatable containers. */
public class InheritedAnnotations {
    @Retention(RetentionPolicy.RUNTIME) @Inherited @interface Inh { String value(); }
    @Retention(RetentionPolicy.RUNTIME) @interface NotInh { }
    @Retention(RetentionPolicy.RUNTIME) @Inherited @interface Level { int value(); }

    @Retention(RetentionPolicy.RUNTIME) @Repeatable(Roles.class) @interface Role { String value(); }
    @Retention(RetentionPolicy.RUNTIME) @interface Roles { Role[] value(); }
    @Retention(RetentionPolicy.RUNTIME) @Inherited @Repeatable(Tags.class) @interface Tg { String value(); }
    @Retention(RetentionPolicy.RUNTIME) @Inherited @interface Tags { Tg[] value(); }

    @Inh("base") @NotInh @Level(1) static class Base { }
    static class Mid extends Base { }
    @Level(2) static class Leaf extends Mid { }
    @Inh("iface") interface Marked { }
    static class Impl implements Marked { }
    static class Plain { }

    @Role("a") @Role("b") static class Multi { }
    @Role("single") static class Single { }
    static class Sub extends Multi { }
    @Tg("1") @Tg("2") static class TBase { }
    static class TSub extends TBase { }
    @Tg("own") static class TOwn extends TBase { }

    static class Members {
        @Role("m1") @Role("m2") public void both() { }
        @Role("only") public void one() { }
        @Role("f1") @Role("f2") @Role("f3") public int field;
        public void none() { }
    }

    static String list(Annotation[] as) {
        return as.length + Arrays.toString(as);
    }

    public static void main(String[] args) throws Exception {
        System.out.println(list(Base.class.getAnnotations()));
        System.out.println(list(Mid.class.getAnnotations()) + " " + Mid.class.getDeclaredAnnotations().length);
        System.out.println(list(Leaf.class.getAnnotations()) + " " + list(Leaf.class.getDeclaredAnnotations()));
        System.out.println(list(Impl.class.getAnnotations()) + " " + Impl.class.isAnnotationPresent(Inh.class));
        System.out.println(Marked.class.getAnnotation(Inh.class) + " " + list(Plain.class.getAnnotations()));
        System.out.println(Mid.class.isAnnotationPresent(Inh.class) + " " + Mid.class.isAnnotationPresent(NotInh.class)
                + " " + (Mid.class.getDeclaredAnnotation(Inh.class) == null) + " " + (Base.class.getDeclaredAnnotation(Inh.class) != null));
        System.out.println(Leaf.class.getAnnotation(Level.class).value() + " " + Mid.class.getAnnotation(Level.class).value()
                + " " + Leaf.class.getAnnotation(Inh.class).value());
        System.out.println((Base.class.getAnnotation(Inh.class) == Base.class.getAnnotation(Inh.class))
                + " " + (Mid.class.getAnnotation(Inh.class) == Base.class.getAnnotation(Inh.class)));

        System.out.println(list(Multi.class.getAnnotations()) + " " + (Multi.class.getAnnotation(Role.class) == null));
        System.out.println(Multi.class.getAnnotation(Roles.class).value().length);
        System.out.println(list(Multi.class.getAnnotationsByType(Role.class)) + " " + Multi.class.getAnnotationsByType(Roles.class).length);
        System.out.println(list(Single.class.getAnnotationsByType(Role.class)) + " " + Single.class.getAnnotationsByType(Roles.class).length
                + " " + (Single.class.getAnnotation(Role.class) != null));
        System.out.println(list(Multi.class.getDeclaredAnnotationsByType(Role.class)) + " " + Plain.class.getAnnotationsByType(Role.class).length);
        System.out.println(Sub.class.getAnnotationsByType(Role.class).length + " " + Sub.class.getDeclaredAnnotationsByType(Role.class).length
                + " " + Sub.class.getAnnotations().length);
        System.out.println(list(TSub.class.getAnnotationsByType(Tg.class)) + " " + list(TSub.class.getAnnotations())
                + " " + TSub.class.getDeclaredAnnotationsByType(Tg.class).length);
        System.out.println(list(TOwn.class.getAnnotationsByType(Tg.class)) + " " + list(TOwn.class.getAnnotations()));

        Method both = Members.class.getMethod("both"), one = Members.class.getMethod("one"), none = Members.class.getMethod("none");
        System.out.println(list(both.getAnnotations()) + " " + (both.getAnnotation(Role.class) == null));
        System.out.println(list(both.getAnnotationsByType(Role.class)) + " " + both.getDeclaredAnnotationsByType(Role.class).length);
        System.out.println(list(one.getAnnotationsByType(Role.class)) + " " + one.getAnnotationsByType(Roles.class).length
                + " " + list(none.getAnnotationsByType(Role.class)));
        System.out.println(one.getDeclaredAnnotation(Role.class) + " " + both.getDeclaredAnnotation(Role.class));
        Field f = Members.class.getField("field");
        System.out.println(list(f.getAnnotationsByType(Role.class)) + " " + f.getAnnotations().length
                + " " + f.getAnnotation(Roles.class).value()[2].value());
        System.out.println("done");
    }
}
