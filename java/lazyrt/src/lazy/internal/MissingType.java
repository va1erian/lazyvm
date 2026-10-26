package lazy.internal;

/** Stands in for an annotation value whose class cannot be loaded; reading the element rethrows. */
final class MissingType {
    final TypeNotPresentException error;

    MissingType(TypeNotPresentException error) {
        this.error = error;
    }
}
