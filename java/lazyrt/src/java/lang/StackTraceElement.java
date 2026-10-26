package java.lang;

public final class StackTraceElement implements java.io.Serializable {
    private final String declaringClass;
    private final String methodName;
    private final String fileName;
    private final int lineNumber;

    public StackTraceElement(String declaringClass, String methodName, String fileName,
            int lineNumber) {
        if (declaringClass == null) {
            throw new NullPointerException("Declaring class is null");
        }
        if (methodName == null) {
            throw new NullPointerException("Method name is null");
        }
        this.declaringClass = declaringClass;
        this.methodName = methodName;
        this.fileName = fileName;
        this.lineNumber = lineNumber;
    }

    public String getFileName() {
        return fileName;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getClassName() {
        return declaringClass;
    }

    public String getMethodName() {
        return methodName;
    }

    public boolean isNativeMethod() {
        return lineNumber == -2;
    }

    public String toString() {
        String where;
        if (isNativeMethod()) {
            where = "Native Method";
        } else if (fileName != null && lineNumber >= 0) {
            where = fileName + ":" + lineNumber;
        } else if (fileName != null) {
            where = fileName;
        } else {
            where = "Unknown Source";
        }
        return declaringClass + "." + methodName + "(" + where + ")";
    }

    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof StackTraceElement)) {
            return false;
        }
        StackTraceElement e = (StackTraceElement) obj;
        return declaringClass.equals(e.declaringClass) && methodName.equals(e.methodName)
                && lineNumber == e.lineNumber && same(fileName, e.fileName);
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    public int hashCode() {
        int result = 31 * declaringClass.hashCode() + methodName.hashCode();
        result = 31 * result + (fileName == null ? 0 : fileName.hashCode());
        return 31 * result + lineNumber;
    }
}
