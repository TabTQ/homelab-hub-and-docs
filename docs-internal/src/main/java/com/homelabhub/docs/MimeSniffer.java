package com.homelabhub.docs;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Decides an uploaded asset's type from its bytes (plan 5.3), never from the browser's Content-Type. Only the types the
 * editor can show or offer for download are accepted: png, jpeg, webp, gif, svg, pdf, zip, and text (plain, json, yaml).
 */
public final class MimeSniffer {

    private MimeSniffer() {}

    /** @return the MIME type, or null when the file is not an accepted type */
    public static String sniff(byte[] head, String fileName) {
        if (startsWith(head, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(head, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(head, 'G', 'I', 'F', '8')) {
            return "image/gif";
        }
        if (head.length >= 12 && startsWith(head, 'R', 'I', 'F', 'F') && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return "image/webp";
        }
        if (startsWith(head, '%', 'P', 'D', 'F', '-')) {
            return "application/pdf";
        }
        if (startsWith(head, 'P', 'K', 3, 4) || startsWith(head, 'P', 'K', 5, 6)) {
            return "application/zip";
        }
        if (!isUtf8Text(head)) {
            return null;
        }
        String text = new String(head, StandardCharsets.UTF_8).stripLeading().toLowerCase(Locale.ROOT);
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if ((text.startsWith("<svg") || text.startsWith("<?xml") && text.contains("<svg")) || (name.endsWith(".svg") && text.contains("<svg"))) {
            return "image/svg+xml";
        }
        if (name.endsWith(".json") || text.startsWith("{") || text.startsWith("[")) {
            return "application/json";
        }
        if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            return "application/yaml";
        }
        if (text.startsWith("<")) {
            // HTML or XML that is not an SVG: stored as plain text so a browser never renders it.
            return "text/plain";
        }
        return "text/plain";
    }

    public static boolean isImage(String mime) {
        return mime != null && mime.startsWith("image/");
    }

    private static boolean startsWith(byte[] b, int... sig) {
        if (b.length < sig.length) {
            return false;
        }
        for (int i = 0; i < sig.length; i++) {
            if ((b[i] & 0xff) != sig[i]) {
                return false;
            }
        }
        return true;
    }

    /** UTF-8 with no NUL and few control characters. A multi-byte character cut at the end of the sample is fine. */
    static boolean isUtf8Text(byte[] head) {
        if (head.length == 0) {
            return true;
        }
        int end = head.length;
        for (int back = 0; back < 3 && end > 0 && (head[end - 1] & 0xC0) == 0x80; back++) {
            end--;
        }
        if (end > 0 && (head[end - 1] & 0xC0) == 0xC0) {
            end--;
        }
        try {
            String s = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(head, 0, end)).toString();
            long bad = s.chars().filter(c -> c < 0x20 && c != '\n' && c != '\r' && c != '\t' && c != '\f').count();
            return bad == 0;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
