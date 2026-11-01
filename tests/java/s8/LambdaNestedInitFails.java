package s8;

/** Lambda linking inside an interface's initializer: a BootstrapMethodError is not wrapped again. */
public class LambdaNestedInitFails {
    interface J {
        int X = boom();

        default void d() {
        }

        void run();
    }

    interface I {
        int X = make();

        default void d() {
        }

        void run();
    }

    interface K {
        int X = bootstrapError();

        default void d() {
        }

        void run();
    }

    static int boom() {
        throw new IllegalStateException("boom");
    }

    static int bootstrapError() {
        throw new BootstrapMethodError("x");
    }

    static int make() {
        J j = () -> { };
        j.run();
        return 1;
    }

    static void chain(Throwable t) {
        for (; t != null; t = t.getCause()) {
            System.out.println("  " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    public static void main(String[] args) {
        try {
            I i = () -> { };
            i.run();
        } catch (Throwable t) {
            System.out.println("nested:");
            chain(t);
        }
        try {
            K k = () -> { };
            k.run();
        } catch (Throwable t) {
            System.out.println("direct:");
            chain(t);
        }
    }
}
