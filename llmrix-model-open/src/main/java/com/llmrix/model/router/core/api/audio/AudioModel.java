package com.llmrix.model.router.core.api.audio;

public interface AudioModel {
    AudioResponse transcribe(AudioTextRequest request);

    AudioResponse translate(AudioTextRequest request);

    AudioResponse speech(SpeechRequest request);
}
