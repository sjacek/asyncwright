package io.github.sjacek.asyncwright.parser;

import com.asyncapi.schemas.asyncapi.AsyncAPISchema;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.util.List;

/** Shared model mapping: JSON instance values must never be deserialized as schema objects. */
final class ModelMapping {
    private ModelMapping() { }

    static ObjectMapper mapper() {
        return new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .addMixIn(AsyncAPISchema.class, InstanceValues.class);
    }

    private abstract static class InstanceValues {
        // Upstream's generated setters also carry deserializer annotations.
        @JsonProperty("const") @JsonDeserialize(using = InstanceValueDeserializer.class)
        abstract void setConstValue(Object value);
        @JsonProperty("default") @JsonDeserialize(using = InstanceValueDeserializer.class)
        abstract void setDefaultValue(Object value);
        @JsonProperty("examples") @JsonDeserialize(contentUsing = InstanceValueDeserializer.class)
        abstract void setExamples(List<Object> value);
    }

    public static final class InstanceValueDeserializer extends JsonDeserializer<Object> {
        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return context.readValue(parser, Object.class);
        }
    }
}
