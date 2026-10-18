package com.homelabhub.docs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Reads an uploaded webapp (plan 5.3): a single index.html, or a ZIP. Every entry is checked before anything is stored:
 * no absolute paths, no "..", no backslashes or drive letters, no symlinks, allowed extensions only, at most 500 files and
 * 60 MB unpacked, compression ratio at most 100. Sizes are counted while reading, never taken from the ZIP headers.
 */
public final class BundleReader {

    public static final int MAX_ENTRIES = 500;
    public static final long MAX_UNPACKED = 60L * 1024 * 1024;
    public static final int MAX_RATIO = 100;

    static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"), Map.entry("json", "application/json"),
            Map.entry("map", "application/json"), Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
            Map.entry("svg", "image/svg+xml"), Map.entry("ico", "image/x-icon"), Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"), Map.entry("ttf", "font/ttf"), Map.entry("otf", "font/otf"),
            Map.entry("wasm", "application/wasm"), Map.entry("csv", "text/csv; charset=utf-8"),
            Map.entry("txt", "text/plain; charset=utf-8"), Map.entry("md", "text/markdown; charset=utf-8"),
            Map.entry("xml", "application/xml"));

    /** One file of the bundle, still in memory. */
    public record Entry(String path, byte[] bytes, String mime) {}

    public static final class BadBundleException extends IllegalArgumentException {
        public BadBundleException(String message) {
            super(message);
        }
    }

    private BundleReader() {}

    /** A single uploaded HTML file becomes a one-file bundle. */
    public static List<Entry> singleHtml(byte[] bytes) {
        return List.of(new Entry("index.html", bytes, TYPES.get("html")));
    }

    public static boolean looksLikeZip(byte[] head) {
        return head.length >= 4 && head[0] == 'P' && head[1] == 'K' && (head[2] == 3 || head[2] == 5) && (head[3] == 4 || head[3] == 6);
    }

    /** Reads a whole ZIP (the upload limit keeps it small enough to hold in memory). */
    public static List<Entry> zip(byte[] data) throws IOException {
        Set<String> links = symlinks(data);
        long compressedSize = data.length;
        Map<String, Entry> entries = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String raw = e.getName();
                if (e.isDirectory()) {
                    checkPath(raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw, true);
                    continue;
                }
                if (links.contains(raw)) {
                    throw new BadBundleException("The ZIP contains a symbolic link (" + shown(raw) + "). Links are not allowed.");
                }
                String path = checkPath(raw, false);
                if (entries.size() >= MAX_ENTRIES) {
                    throw new BadBundleException("The ZIP has more than " + MAX_ENTRIES + " files.");
                }
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[64 * 1024];
                int n;
                while ((n = z.read(chunk)) > 0) {
                    total += n;
                    if (total > MAX_UNPACKED) {
                        throw new BadBundleException("The ZIP unpacks to more than 60 MB.");
                    }
                    if (compressedSize > 0 && total > compressedSize * MAX_RATIO && total > 1024 * 1024) {
                        throw new BadBundleException("The ZIP is compressed suspiciously well (more than 100:1). It looks like a zip bomb.");
                    }
                    buf.write(chunk, 0, n);
                }
                if (entries.containsKey(path.toLowerCase(Locale.ROOT))) {
                    throw new BadBundleException("The ZIP contains " + shown(path) + " twice.");
                }
                entries.put(path.toLowerCase(Locale.ROOT), new Entry(path, buf.toByteArray(), mime(path)));
            }
        } catch (ZipException ex) {
            throw new BadBundleException("The file is not a valid ZIP.");
        }
        List<Entry> list = stripSingleFolder(new ArrayList<>(entries.values()));
        if (list.stream().noneMatch(x -> x.path().equals("index.html"))) {
            throw new BadBundleException("The ZIP has no index.html at the top (or inside one top folder).");
        }
        return list;
    }

    /** "app/index.html, app/x.js" → "index.html, x.js" when everything is inside one folder and there is no top index.html. */
    static List<Entry> stripSingleFolder(List<Entry> list) {
        if (list.isEmpty() || list.stream().anyMatch(x -> x.path().equals("index.html"))) {
            return list;
        }
        String first = list.get(0).path();
        int slash = first.indexOf('/');
        if (slash < 0) {
            return list;
        }
        String prefix = first.substring(0, slash + 1);
        if (!list.stream().allMatch(x -> x.path().startsWith(prefix))) {
            return list;
        }
        return list.stream().map(x -> new Entry(x.path().substring(prefix.length()), x.bytes(), x.mime())).toList();
    }

    static String checkPath(String raw, boolean directory) {
        if (raw == null || raw.isEmpty() || raw.length() > 300) {
            throw new BadBundleException("The ZIP contains an entry with an empty or very long name.");
        }
        if (raw.indexOf('\\') >= 0 || raw.indexOf('\0') >= 0) {
            throw new BadBundleException("The ZIP contains a path with a backslash (" + shown(raw) + ").");
        }
        if (raw.startsWith("/") || raw.matches("^[A-Za-z]:.*")) {
            throw new BadBundleException("The ZIP contains an absolute path (" + shown(raw) + ").");
        }
        String[] parts = raw.split("/", -1);
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".") || p.equals("..")) {
                throw new BadBundleException("The ZIP contains a path that leaves its folder (" + shown(raw) + ").");
            }
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c < 0x20 || c == 0x7f || c == ':' || c == '?' || c == '#' || c == '%') {
                    throw new BadBundleException("The ZIP contains a file name with characters that are not allowed (" + shown(raw) + ").");
                }
            }
        }
        if (directory) {
            return raw;
        }
        if (parts[parts.length - 1].startsWith(".")) {
            throw new BadBundleException("Hidden files are not allowed (" + shown(raw) + "). Remove them from the ZIP.");
        }
        if (mime(raw) == null) {
            throw new BadBundleException("The file type of " + shown(raw) + " is not allowed. Allowed: " + String.join(", ", allowedExtensions()) + ".");
        }
        return raw;
    }

    static String mime(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? null : TYPES.get(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    static List<String> allowedExtensions() {
        return TYPES.keySet().stream().sorted().toList();
    }

    /**
     * Names of the entries the central directory marks as Unix symlinks (made by Unix, mode S_IFLNK in the upper 16 bits of
     * the external attributes). ZipInputStream never shows these attributes, so the directory is read here directly.
     */
    static Set<String> symlinks(byte[] zip) {
        Set<String> out = new java.util.HashSet<>();
        int eocd = -1;
        for (int i = zip.length - 22; i >= Math.max(0, zip.length - 22 - 65535); i--) {
            if (u32(zip, i) == 0x06054b50L) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            throw new BadBundleException("The file is not a valid ZIP.");
        }
        int count = u16(zip, eocd + 10);
        long offset = u32(zip, eocd + 16);
        int p = (int) Math.min(offset, Integer.MAX_VALUE);
        for (int n = 0; n < count; n++) {
            if (p < 0 || p + 46 > zip.length || u32(zip, p) != 0x02014b50L) {
                throw new BadBundleException("The file is not a valid ZIP.");
            }
            int madeBy = u16(zip, p + 4) >> 8;
            int nameLen = u16(zip, p + 28);
            int extraLen = u16(zip, p + 30);
            int commentLen = u16(zip, p + 32);
            long ext = u32(zip, p + 38);
            if (p + 46 + nameLen > zip.length) {
                throw new BadBundleException("The file is not a valid ZIP.");
            }
            if (madeBy == 3 && ((ext >>> 16) & 0xF000) == 0xA000) {
                out.add(new String(zip, p + 46, nameLen, StandardCharsets.UTF_8));
            }
            p += 46 + nameLen + extraLen + commentLen;
        }
        return out;
    }

    private static int u16(byte[] b, int i) {
        return (b[i] & 0xff) | (b[i + 1] & 0xff) << 8;
    }

    private static long u32(byte[] b, int i) {
        return (u16(b, i) | (long) u16(b, i + 2) << 16) & 0xffffffffL;
    }

    private static String shown(String s) {
        String flat = s.replaceAll("[\\p{Cntrl}]", "?");
        return flat.length() > 80 ? flat.substring(0, 80) + "…" : flat;
    }

}
