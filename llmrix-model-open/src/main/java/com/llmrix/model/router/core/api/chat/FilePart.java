package com.llmrix.model.router.core.api.chat;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

/** A remote URL, data URL, or provider-uploaded file ID. */
@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class FilePart implements ContentPart {
    private final String url;
    private final String fileId;
    private final String filename;

    public FilePart(String url) {
        this(url, null);
    }

    public FilePart(String url, String filename) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("file url or data must not be blank");
        if (filename != null && filename.isBlank()) throw new IllegalArgumentException("file filename must not be blank");
        this.url = url;
        this.fileId = null;
        this.filename = filename;
    }

    private FilePart(String fileId, String filename, boolean uploaded) {
        if (fileId == null || fileId.isBlank()) throw new IllegalArgumentException("file id must not be blank");
        if (filename != null && filename.isBlank()) throw new IllegalArgumentException("file filename must not be blank");
        this.url = null;
        this.fileId = fileId;
        this.filename = filename;
    }

    public static FilePart fileId(String fileId) {
        return fileId(fileId, null);
    }

    public static FilePart fileId(String fileId, String filename) {
        return new FilePart(fileId, filename, true);
    }
}
