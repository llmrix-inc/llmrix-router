package com.llmrix.model.router.integrations.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.llmrix.model.router.core.api.ChatRequest;
import com.llmrix.model.router.core.api.ChatResponse;
import com.llmrix.model.router.core.api.ResponseFormat;
import com.llmrix.model.router.core.exception.InvalidRequestException;

import java.util.Objects;

/** JSON Schema validator backed by networknt; the dependency remains optional. */
public final class NetworkntResponseValidator implements ResponseValidator {
    private final ObjectMapper mapper;

    public NetworkntResponseValidator() { this(new ObjectMapper()); }

    public NetworkntResponseValidator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void validate(ChatRequest request, ChatResponse response) {
        ResponseFormat format = request.responseFormat();
        if (format == null || format.type() != ResponseFormat.Type.JSON_SCHEMA) return;
        try {
            JsonNode schemaNode = mapper.valueToTree(format.schema());
            JsonNode responseNode = mapper.readTree(response.text());
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(schemaNode);
            var errors = schema.validate(responseNode);
            if (!errors.isEmpty()) {
                throw new InvalidRequestException("model response does not match response schema: "
                        + errors.iterator().next().getMessage());
            }
        } catch (InvalidRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidRequestException("cannot validate model response against response schema");
        }
    }
}
