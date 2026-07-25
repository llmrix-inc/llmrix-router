package com.llmrix.model.router.integrations.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.llmrix.model.router.core.api.ChatRequest;
import com.llmrix.model.router.core.api.ChatResponse;
import com.llmrix.model.router.core.api.ToolDefinition;
import com.llmrix.model.router.core.exception.InvalidRequestException;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Validates model-produced function arguments against request tool schemas. */
public final class NetworkntToolArgumentsValidator implements ResponseValidator {
    private final ObjectMapper mapper;

    public NetworkntToolArgumentsValidator() { this(new ObjectMapper()); }

    public NetworkntToolArgumentsValidator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void validate(ChatRequest request, ChatResponse response) {
        if (response.toolCalls().isEmpty()) return;
        Map<String, ToolDefinition> definitions = request.tools().stream()
                .collect(Collectors.toMap(ToolDefinition::name, Function.identity()));
        response.toolCalls().forEach(call -> {
            ToolDefinition definition = definitions.get(call.name());
            if (definition == null) throw new InvalidRequestException(
                    "model called an undeclared tool: " + call.name());
            try {
                JsonNode arguments = mapper.readTree(call.arguments());
                if (arguments == null || !arguments.isObject()) {
                    throw new InvalidRequestException("tool arguments must be a JSON object: " + call.name());
                }
                if (!definition.parameters().isEmpty()) {
                    var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                            .getSchema(mapper.valueToTree(definition.parameters()));
                    var errors = schema.validate(arguments);
                    if (!errors.isEmpty()) throw new InvalidRequestException(
                            "tool arguments do not match schema for " + call.name() + ": "
                                    + errors.iterator().next().getMessage());
                }
            } catch (InvalidRequestException e) {
                throw e;
            } catch (Exception e) {
                throw new InvalidRequestException("tool arguments are not valid JSON for " + call.name());
            }
        });
    }
}
