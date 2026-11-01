package lang;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.MissingResourceException;

/** The small exception classes programs throw or catch from these APIs. */
public class ApiExceptions {
    enum Color { RED }

    static void describe(Throwable t) {
        System.out.println(t + " | message " + t.getMessage() + " | cause " + t.getCause()
                + " | super " + t.getClass().getSuperclass().getName());
    }

    static void initCause(Throwable t) {
        try {
            t.initCause(new Error("second"));
            System.out.println("initCause ok, now " + t.getCause());
        } catch (IllegalStateException e) {
            System.out.println("initCause " + e.getClass().getName());
        }
    }

    public static void boom() throws Exception {
        throw new Exception("from boom");
    }

    public static void main(String[] args) throws Exception {
        EnumConstantNotPresentException e1 = new EnumConstantNotPresentException(Color.class, "BLUE");
        describe(e1);
        System.out.println(e1.enumType().getName() + " " + e1.constantName());
        initCause(e1);
        try {
            new EnumConstantNotPresentException(null, "X");
        } catch (NullPointerException e) {
            System.out.println("null enum type " + e.getClass().getName());
        }
        describe(new EnumConstantNotPresentException(Color.class, null));

        MissingResourceException e2 = new MissingResourceException("text", "some.Bundle", "the.key");
        describe(e2);
        System.out.println(e2.getClassName() + " " + e2.getKey());
        initCause(e2);
        MissingResourceException e3 = new MissingResourceException(null, null, null);
        describe(e3);
        System.out.println(e3.getClassName() + " " + e3.getKey());

        Throwable why = new RuntimeException("why");
        TypeNotPresentException e4 = new TypeNotPresentException("no.Such", why);
        describe(e4);
        System.out.println(e4.typeName());
        initCause(e4);
        TypeNotPresentException e5 = new TypeNotPresentException(null, null);
        describe(e5);
        initCause(e5);

        InvocationTargetException e6 = new InvocationTargetException(why);
        describe(e6);
        System.out.println(e6.getTargetException());
        initCause(e6);
        describe(new InvocationTargetException(why, "text"));
        describe(new InvocationTargetException(null));
        describe(new InvocationTargetException(null, "text"));
        describe(new InvocationTargetException(why) { });

        UndeclaredThrowableException e7 = new UndeclaredThrowableException(why);
        describe(e7);
        System.out.println(e7.getUndeclaredThrowable());
        initCause(e7);
        describe(new UndeclaredThrowableException(why, "text"));
        describe(new UndeclaredThrowableException(null));

        Method boom = ApiExceptions.class.getMethod("boom");
        try {
            boom.invoke(null);
        } catch (InvocationTargetException e) {
            describe(e);
            describe(e.getTargetException());
        }
    }
}
