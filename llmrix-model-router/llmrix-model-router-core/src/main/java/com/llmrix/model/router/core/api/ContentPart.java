package com.llmrix.model.router.core.api;

public sealed interface ContentPart permits TextPart, ImagePart, AudioPart, ToolCallPart, ToolResultPart { }
