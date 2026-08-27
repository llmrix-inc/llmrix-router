package com.llmrix.model.router.core.model;

/** Operations that a model target can execute. */
public enum ModelOperation {
    CHAT,
    EMBEDDINGS,
    RERANK,
    AUDIO_TRANSCRIPTION,
    AUDIO_TRANSLATION,
    TEXT_TO_SPEECH,
    IMAGE_GENERATION,
    IMAGE_EDIT,
    VIDEO_GENERATION
}
