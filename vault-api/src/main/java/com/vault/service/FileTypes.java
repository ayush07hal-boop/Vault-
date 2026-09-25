package com.vault.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** File categories (the "Type" filter and storage breakdown), derived from the file extension. */
public final class FileTypes {

    public enum Category {
        PDF("pdf"),
        DOCUMENT("doc", "docx", "txt", "rtf", "odt", "md"),
        SPREADSHEET("xls", "xlsx", "csv", "ods"),
        PRESENTATION("ppt", "pptx", "odp", "key"),
        IMAGE("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "tif", "tiff"),
        VIDEO("mp4", "mov", "avi", "mkv", "webm", "wmv", "flv", "m4v"),
        AUDIO("mp3", "wav", "flac", "aac", "ogg", "m4a"),
        ARCHIVE("zip", "rar", "7z", "tar", "gz"),
        OTHER();

        private final List<String> extensions;

        Category(String... extensions) {
            this.extensions = List.of(extensions);
        }

        public List<String> extensions() {
            return extensions;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Map<String, Category> BY_EXTENSION = new java.util.HashMap<>();

    static {
        for (Category c : Category.values()) {
            c.extensions().forEach(e -> BY_EXTENSION.put(e, c));
        }
    }

    private FileTypes() {
    }

    public static Category categoryOf(String fileName) {
        if (fileName == null) {
            return Category.OTHER;
        }
        int dot = fileName.lastIndexOf('.');
        String ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return BY_EXTENSION.getOrDefault(ext, Category.OTHER);
    }

    /** Parses the {@code type} query value ("pdf", "image", ...). */
    public static Optional<Category> parse(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        for (Category c : Category.values()) {
            if (c.key().equals(key.toLowerCase(Locale.ROOT))) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}
