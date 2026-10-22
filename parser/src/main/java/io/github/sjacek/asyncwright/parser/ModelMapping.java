package io.github.sjacek.asyncwright.parser;

import static io.github.sjacek.asyncwright.api.AsyncApiFields.*;

import com.asyncapi.schemas.asyncapi.AsyncAPISchema;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.github.sjacek.asyncwright.api.BooleanSchema;

import java.io.IOException;
import java.io.Serial;
import java.util.List;

/** Shared mapping for schema leaves and instance data in the parser, resolver and extension models. */
final class ModelMapping {
    private ModelMapping() { }

    static ObjectMapper mapper() {
        SimpleModule booleans = new SimpleModule(BooleanDeserializer.class.getName());
        booleans.setDeserializerModifier(new BeanDeserializerModifier() {
            @Serial private static final long serialVersionUID = 1L;
            @Override public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription bean,
                                                                     JsonDeserializer<?> deserializer) {
                return bean.getBeanClass() == AsyncAPISchema.class ? new BooleanDeserializer(deserializer) : deserializer;
            }
        });
        return new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .addMixIn(AsyncAPISchema.class, InstanceValues.class)
                .registerModule(booleans);
    }

    private static final class BooleanDeserializer extends DelegatingDeserializer {
        @Serial private static final long serialVersionUID = 1L;
        BooleanDeserializer(JsonDeserializer<?> delegate) { super(delegate); }
        @Override protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> delegate) {
            return new BooleanDeserializer(delegate);
        }
        @Override public Object deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_TRUE || parser.currentToken() == JsonToken.VALUE_FALSE)
                return new BooleanSchema(parser.getBooleanValue());
            return super.deserialize(parser, context);
        }
    }

    private abstract static class InstanceValues {
        // Upstream's generated setters also carry deserializer annotations.
        @JsonProperty(CONST) @JsonDeserialize(using = InstanceValueDeserializer.class)
        abstract void setConstValue(Object value);
        @JsonProperty(DEFAULT) @JsonDeserialize(using = InstanceValueDeserializer.class)
        abstract void setDefaultValue(Object value);
        @JsonProperty(EXAMPLES) @JsonDeserialize(contentUsing = InstanceValueDeserializer.class)
        abstract void setExamples(List<Object> value);
    }

    public static final class InstanceValueDeserializer extends JsonDeserializer<Object> {
        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return context.readValue(parser, Object.class);
        }
    }
}
