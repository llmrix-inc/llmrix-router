package com.llmrix.model.router.core.api.chat;

public sealed interface ContentPart permits TextPart, ImagePart, AudioPart, VideoPart, FilePart, ToolCallPart, ToolResultPart {
}
