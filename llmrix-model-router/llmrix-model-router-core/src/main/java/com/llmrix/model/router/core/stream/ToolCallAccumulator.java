package com.llmrix.model.router.core.stream;

import com.llmrix.model.router.core.api.chat.ChatChunk;
import com.llmrix.model.router.core.api.chat.ToolCallDelta;
import com.llmrix.model.router.core.api.chat.ToolCallPart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates indexed streaming tool call deltas into complete tool calls.
 */
public final class ToolCallAccumulator {

    private final Map<Integer, PartialCall> calls = new HashMap<>();

    public synchronized void add(ChatChunk chunk) {
        chunk.toolCallDeltas().forEach(this::add);
    }

    public synchronized void add(ToolCallDelta delta) {
        PartialCall partial = calls.computeIfAbsent(delta.index(), ignored -> new PartialCall());
        if (delta.id() != null) partial.id = consistent(partial.id, delta.id(), "id", delta.index());
        if (delta.name() != null) partial.name = consistent(partial.name, delta.name(), "name", delta.index());
        partial.arguments.append(delta.arguments());
    }

    public synchronized List<ToolCallPart> finish() {
        List<Map.Entry<Integer, PartialCall>> ordered = new ArrayList<>(calls.entrySet());
        ordered.sort(Comparator.comparingInt(Map.Entry::getKey));
        return ordered.stream().map(entry -> {
            PartialCall call = entry.getValue();
            if (call.id == null || call.name == null) {
                throw new IllegalStateException("incomplete tool call delta at index " + entry.getKey());
            }
            return new ToolCallPart(call.id, call.name, call.arguments.toString());
        }).toList();
    }

    public synchronized boolean isEmpty() {
        return calls.isEmpty();
    }

    private static String consistent(String current, String next, String field, int index) {
        if (current != null && !current.equals(next)) {
            throw new IllegalArgumentException("conflicting tool call " + field + " at index " + index);
        }
        return next;
    }

    private static final class PartialCall {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
