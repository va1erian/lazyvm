package java.lang.annotation;

public class IncompleteAnnotationException extends RuntimeException {
    private final Class<? extends Annotation> annotationType;
    private final String elementName;

    public IncompleteAnnotationException(Class<? extends Annotation> annotationType, String elementName) {
        super(annotationType.getName() + " missing element " + elementName);
        this.annotationType = annotationType;
        this.elementName = elementName;
    }

    public Class<? extends Annotation> annotationType() {
        return annotationType;
    }

    public String elementName() {
        return elementName;
    }
}
