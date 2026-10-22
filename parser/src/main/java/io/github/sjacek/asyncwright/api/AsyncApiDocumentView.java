package io.github.sjacek.asyncwright.api;

import com.asyncapi.v3._0_0.model.AsyncAPI;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Document access shared by rules and generators; references remain in the typed model.
 * ExtensionExecutor supplies a fresh model for each invocation, while source/target trees are defensive copies.
 */
public interface AsyncApiDocumentView {
    AsyncAPI model();
    JsonNode source();
    SourceLocation location(String pointer);
    AsyncApiReferences references();
}
