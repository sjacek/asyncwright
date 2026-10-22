package io.github.sjacek.asyncwright.api;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Resolves the position of an object or reference, without expanding the document graph. */
public interface AsyncApiReferences {
    Optional<ResolvedReference> resolve(String pointer);
    <T> Optional<T> resolve(String pointer, Class<T> type) throws IOException;
    List<SourceLocation> related(String definitionPointer);
}
