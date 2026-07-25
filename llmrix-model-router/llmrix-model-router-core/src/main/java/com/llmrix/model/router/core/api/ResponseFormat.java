package com.llmrix.model.router.core.api;

import java.util.Map;

public record ResponseFormat(Type type, String name, String description,
                             Map<String, Object> schema, Boolean strict) {
    public enum Type { TEXT, JSON_OBJECT, JSON_SCHEMA }

    public ResponseFormat {
        if (type == null) throw new IllegalArgumentException("response format type must not be null");
        if (type == Type.JSON_SCHEMA && (name == null || name.isBlank())) {
            throw new IllegalArgumentException("json_schema response format requires a name");
        }
        schema = schema == null ? Map.of() : Map.copyOf(schema);
    }

    public static ResponseFormat text() { return new ResponseFormat(Type.TEXT, null, null, Map.of(), null); }
    public static ResponseFormat jsonObject() { return new ResponseFormat(Type.JSON_OBJECT, null, null, Map.of(), null); }
    public static ResponseFormat jsonSchema(String name, Map<String, Object> schema, boolean strict) {
        return new ResponseFormat(Type.JSON_SCHEMA, name, null, schema, strict);
    }
}
