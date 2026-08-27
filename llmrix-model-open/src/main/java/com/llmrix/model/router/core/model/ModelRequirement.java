package com.llmrix.model.router.core.model;

/** Cross-cutting requirement used by request routing hints. */
public enum ModelRequirement {
    CHAT,
    CHAT_STREAMING,
    CODE,
    TOOLS,
    STRUCTURED_OUTPUT,
    PROMPT_CACHE,
    VISION,
    REASONING,
    LONG_CONTEXT,
    VIDEO_INPUT,
    FILE_INPUT,
    AUDIO_INPUT,
    EMBEDDINGS,
    RERANK,
    AUDIO_TRANSCRIPTION,
    AUDIO_TRANSLATION,
    TEXT_TO_SPEECH,
    IMAGE_GENERATION,
    IMAGE_EDIT,
    VIDEO_GENERATION
}
