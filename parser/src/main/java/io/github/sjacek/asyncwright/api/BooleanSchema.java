package io.github.sjacek.asyncwright.api;

import com.asyncapi.schemas.asyncapi.AsyncAPISchema;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A boolean schema leaf compatible with the model's AsyncAPISchema-typed fields.
 * Read value() before inspecting object-schema properties. Inherited object-property
 * setters do not change this leaf's boolean value or its JSON representation.
 */
public final class BooleanSchema extends AsyncAPISchema {
    private final boolean value;

    /**
     * Creates an unconstrained or impossible schema leaf.
     * @param value {@code true} accepts every instance; {@code false} accepts none
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public BooleanSchema(boolean value) { this.value = value; }

    /**
     * Returns the schema assertion and its JSON representation.
     * @return the boolean supplied at construction
     */
    @JsonValue
    public boolean value() { return value; }

    @Override public boolean equals(Object other) {
        return other instanceof BooleanSchema candidate && value == candidate.value;
    }

    @Override protected boolean canEqual(Object other) { return other instanceof BooleanSchema; }
    @Override public int hashCode() { return Boolean.hashCode(value); }
    @Override public String toString() { return "BooleanSchema[value=" + value + "]"; }
}
