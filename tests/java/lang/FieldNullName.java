package lang;

/** A null member name is a NullPointerException, before any lookup. */
public class FieldNullName {
    public int present;

    interface Op {
        Object run() throws Exception;
    }

    static void attempt(String name, Op op) {
        try {
            System.out.println(name + ": " + op.run());
        } catch (Exception e) {
            System.out.println(name + ": " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        attempt("getField", () -> FieldNullName.class.getField(null));
        attempt("getDeclaredField", () -> FieldNullName.class.getDeclaredField(null));
        attempt("getField absent", () -> FieldNullName.class.getField("absent"));
        attempt("getDeclaredField absent", () -> FieldNullName.class.getDeclaredField("absent"));
        attempt("getField present", () -> FieldNullName.class.getField("present").getName());
    }
}
