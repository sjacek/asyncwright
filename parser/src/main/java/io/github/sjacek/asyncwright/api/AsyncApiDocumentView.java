package io.github.sjacek.asyncwright.api;

import com.asyncapi.v3._0_0.model.AsyncAPI;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Document access shared by rules and generators; references remain in the typed model.
 * ExtensionExecutor supplies a fresh model for each invocation, while source/target trees are defensive copies.
 */
public interface AsyncApiDocumentView {
    /**
     * Returns the typed model with Reference Objects preserved.
     * @return mutable model owned by this invocation; changes do not affect later extensions
     */
    AsyncAPI model();
    /**
     * Returns the original validated source tree.
     * @return a fresh defensive copy, including ignored reference siblings and instance values
     */
    JsonNode source();
    /**
     * Locates a source value, falling back to its nearest existing ancestor.
     * @param pointer document JSON Pointer, or the empty string for the root
     * @return one-based location retaining the requested pointer
     */
    SourceLocation location(String pointer);
    /**
     * Returns the resolver associated with the original document.
     * @return resolver independent of mutations to the typed model
     */
    AsyncApiReferences references();
}
