# Coding Standards

## Declaration Spacing

- `LAYOUT-01`: Separate consecutive methods and constructors with exactly one empty line. Keep each declaration's documentation comments and annotations attached to that declaration; place the separating empty line before its documentation or annotations, not between them and the declaration.
- `LAYOUT-02`: In entity classes, separate consecutive fields (attributes) with exactly one empty line. Keep field comments and annotations attached to their field. For Java persistence models, this includes `@Entity`, `@Embeddable` and `@MappedSuperclass` classes, including fully qualified annotations.

## Annotation Layout

- `LAYOUT-03`: Put each declaration annotation on its own line before a class, interface, enum, record, method, constructor or field. Start the next annotation or the annotated declaration on a new line, aligned at the declaration's indentation. Keep documentation before the annotations; nested annotations inside annotation arguments are part of that argument expression. Parameter and type-use annotations stay next to their parameter or type. Apply the same separate-line layout to TypeScript declaration decorators and Groovy declaration annotations.
