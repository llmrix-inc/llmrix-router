package com.llmrix.model.router.integrations.openai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Small immutable multipart/form-data body builder for JDK HttpClient.
 */
public final class MultipartBody {

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
    private final String boundary = "llmrix-" + UUID.randomUUID().toString().replace("-", "");
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private boolean built;

    public MultipartBody text(String name, Object value) {
        if (value == null) return this;
        header("form-data; name=\"" + escape(name) + "\"", null);
        write(value.toString().getBytes(StandardCharsets.UTF_8));
        write(CRLF);
        return this;
    }

    public MultipartBody file(String name, String filename, String mediaType, byte[] data) {
        if (data == null) throw new IllegalArgumentException("multipart file data is required");
        header("form-data; name=\"" + escape(name) + "\"; filename=\"" + escape(filename) + "\"",
                mediaType == null ? "application/octet-stream" : mediaType);
        write(data);
        write(CRLF);
        return this;
    }

    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    public HttpRequest.BodyPublisher publisher() {
        if (!built) {
            write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            built = true;
        }
        return HttpRequest.BodyPublishers.ofByteArray(output.toByteArray());
    }

    private void header(String disposition, String mediaType) {
        if (built) throw new IllegalStateException("multipart body is already built");
        write(("--" + boundary + "\r\nContent-Disposition: " + disposition + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
        if (mediaType != null) write(("Content-Type: " + mediaType + "\r\n").getBytes(StandardCharsets.UTF_8));
        write(CRLF);
    }

    private void write(byte[] value) {
        try {
            output.write(value);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String escape(String value) {
        if (value == null || value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("multipart value must be safe non-blank text");
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
