package io.github.sjacek.asyncwright.parser;

import com.asyncapi.v3._0_0.model.AsyncAPI;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** The published model must preserve Avro before the parser advertises a successful document. */
class AvroModelMappingTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "\"string\"",
            "{\"type\":\"string\"}",
            "[\"null\",\"string\"]",
            "{\"type\":\"record\",\"name\":\"Event\",\"doc\":\"Event docs\",\"aliases\":[\"OldEvent\"],\"fields\":[{\"name\":\"id\",\"type\":\"long\",\"default\":9223372036854775807}]}",
            "{\"type\":\"record\",\"name\":\"Node\",\"fields\":[{\"name\":\"next\",\"type\":[\"null\",\"Node\"],\"default\":null}]}",
            "{\"type\":\"bytes\",\"logicalType\":\"decimal\",\"precision\":12,\"scale\":2}",
            "{\"type\":\"string\",\"logicalType\":\"custom-logical\",\"custom\":{\"$ref\":\"literal.json\",\"values\":[1,true,null]}}",
            "{\"type\":\"string\",\"metadata\":{\"$ref\":\"literal.json\"},\"$ref\":\"also-data.json\"}",
            "{\"type\":\"enum\",\"name\":\"State\",\"symbols\":[\"NEW\",\"OLD\"],\"default\":\"NEW\"}",
            "{\"type\":\"fixed\",\"name\":\"Duration\",\"size\":12,\"logicalType\":\"duration\"}",
            "{\"type\":\"array\",\"items\":{\"type\":\"map\",\"values\":\"long\"}}",
            "{\"$ref\":\"avro/event.avsc\"}"
    })
    void preservesAvroValuesAndReferences(String schema) throws Exception {
        var mapper = ModelMapping.mapper();
        JsonNode expected = mapper.readTree("""
                {"asyncapi":"3.0.0","info":{"title":"Avro","version":"1"},"components":{"schemas":{
                  "Value":{"schemaFormat":"application/vnd.apache.avro;version=1.9.0","schema":%s}}}}
                """.formatted(schema));
        AsyncAPI model = mapper.treeToValue(expected, AsyncAPI.class);
        subset(expected, mapper.valueToTree(model), "");
    }

    private static void subset(JsonNode expected, JsonNode actual, String pointer) {
        if (expected.isObject() && actual.isObject()) {
            for (var field : expected.properties()) subset(field.getValue(), actual.path(field.getKey()), pointer + "/" + field.getKey());
        } else if (expected.isArray() && actual.isArray()) {
            assertEquals(expected.size(), actual.size(), pointer);
            for (int i = 0; i < expected.size(); i++) subset(expected.path(i), actual.path(i), pointer + "/" + i);
        } else if (expected.isNumber() && actual.isNumber()) assertEquals(0, expected.decimalValue().compareTo(actual.decimalValue()), pointer);
        else assertEquals(expected, actual, pointer);
    }
}
