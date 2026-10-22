package io.github.sjacek.asyncwright.parser;

import com.asyncapi.v3._0_0.model.AsyncAPI;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.sjacek.asyncwright.api.SourceLocation;
import io.github.sjacek.asyncwright.api.AsyncApiDocumentView;

/** Typed document with retained references; the resolver reads a separate source tree. */
public final class AsyncApiDocument implements AsyncApiDocumentView {
    private final AsyncAPI model;
    private final SourceDocument source;
    private final ReferenceResolver references;

    AsyncApiDocument(AsyncAPI model, SourceDocument source, ReferenceResolver references) {
        this.model = model;
        this.source = source;
        this.references = references;
    }

    public AsyncAPI model() { return model; }
    public JsonNode source() { return source.root.deepCopy(); }
    public SourceLocation location(String pointer) { return source.location(pointer); }
    public ReferenceResolver references() { return references; }
}
