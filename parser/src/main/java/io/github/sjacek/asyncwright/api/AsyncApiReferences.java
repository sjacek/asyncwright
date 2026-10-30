package io.github.sjacek.asyncwright.api;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Resolves the position of an object or reference, without expanding the document graph. */
public interface AsyncApiReferences {
    /**
     * Reads the snapshot of a validated local Avro file reference at a wrapper's schema position.
     * Resolve document aliases to the wrapper first, then append {@code /schema}. No file is read
     * by this method and the typed model retains the original URI.
     * @param pointer document position of the Avro file Reference Object
     * @return validated file target, or empty for inline/invalid/not-authorized references
     */
    default Optional<ResolvedSchemaFile> resolveSchemaFile(String pointer) { return Optional.empty(); }

    /**
     * Follows validated reference aliases to a concrete source value. Nested references stay intact.
     * For whole binding-map references, resolve the map position before selecting its protocol.
     * @param pointer document position of an object or Reference Object, not the literal {@code $ref} URI
     * @return target and its decoded JSON Pointer, or empty for a missing or unresolvable target
     * @throws IllegalArgumentException if the pointer is not a JSON Pointer
     */
    Optional<ResolvedReference> resolve(String pointer);

    /**
     * Maps a resolved target to a fresh typed value without expanding its nested references.
     * An owner's bindings getter retains a whole-map reference; resolving that owner does not
     * replace its bindings with the target. Resolve the map, then its protocol-specific child.
     * @param <T> requested model type
     * @param pointer document position of an object or Reference Object
     * @param type target Java type, including {@link BooleanSchema} for boolean leaves
     * @return mapped target, or empty when resolution fails
     * @throws IOException if the target cannot be mapped to the requested type
     */
    <T> Optional<T> resolve(String pointer, Class<T> type) throws IOException;

    /**
     * Finds reference usages of a definition or one of its descendants, including aliases.
     * @param definitionPointer document position of the definition or its property
     * @return immutable, deduplicated usage locations in document traversal order
     */
    List<SourceLocation> related(String definitionPointer);
}
