package com.llmrix.model.router.core.api.chat;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Map;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class ResponseFormat {
    public enum Type {TEXT, JSON_OBJECT, JSON_SCHEMA}

    private final Type type;
    private final String name;
    private final String description;
    private final Map<String, Object> schema;
    private final Boolean strict;

    public ResponseFormat(Type type, String name, String description,
                          Map<String, Object> schema, Boolean strict) {
        if (type == null) throw new IllegalArgumentException("response format type must not be null");
        if (type == Type.JSON_SCHEMA && (name == null || name.isBlank())) {
            throw new IllegalArgumentException("json_schema response format requires a name");
        }
        schema = schema == null ? Map.of() : Map.copyOf(schema);
        this.type = type;
        this.name = name;
        this.description = description;
        this.schema = schema;
        this.strict = strict;
    }

    public static ResponseFormat text() {
        return new ResponseFormat(Type.TEXT, null, null, Map.of(), null);
    }

    public static ResponseFormat jsonObject() {
        return new ResponseFormat(Type.JSON_OBJECT, null, null, Map.of(), null);
    }

    public static ResponseFormat jsonSchema(String name, Map<String, Object> schema, boolean strict) {
        return new ResponseFormat(Type.JSON_SCHEMA, name, null, schema, strict);
    }

}
