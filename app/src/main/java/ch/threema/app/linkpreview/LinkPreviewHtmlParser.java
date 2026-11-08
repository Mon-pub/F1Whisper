package ch.threema.app.linkpreview;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * F1Whisper: pure HTML metadata extraction for sender-side link previews (no Android, no network, so it runs on the
 * JVM in unit tests). {@link LinkPreviewFetcher} does the fetching, entity decoding and image work.
 *
 * Sources, first match wins per field:
 * <ul>
 *     <li>title: {@code og:title}, then {@code twitter:title}, then the document {@code <title>}</li>
 *     <li>description: {@code og:description}, then {@code twitter:description}, then {@code description}</li>
 *     <li>image: {@code og:image} (or {@code :url} / {@code :secure_url}), then {@code twitter:image} (or {@code :src})</li>
 * </ul>
 * A meta key may sit in {@code property=} or {@code name=}, in any attribute order, quoted with either quote kind.
 * Values are returned raw (entities still encoded).
 */
public final class LinkPreviewHtmlParser {

    private LinkPreviewHtmlParser() {
    }

    /**
     * A meta tag longer than this is skipped. Real tags are a few hundred characters; the cap bounds the work a hostile
     * page can cause. Everything below is a single forward scan (no regular expressions): a 2 MB page costs linear time.
     */
    private static final int MAX_TAG_CHARS = 16 * 1024;

    /** Bidi and zero-width marks that some sites put around titles ("\u200EGemini"). */
    private static final String INVISIBLE_MARKS = "\u200B\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069\uFEFF";

    public static final class Metadata {
        @Nullable
        public final String title;
        @Nullable
        public final String description;
        @Nullable
        public final String imageUrl;

        Metadata(@Nullable String title, @Nullable String description, @Nullable String imageUrl) {
            this.title = title;
            this.description = description;
            this.imageUrl = imageUrl;
        }

        /** True when there is something to show: a title or an image. */
        public boolean isUsable() {
            return !isBlank(title) || !isBlank(imageUrl);
        }
    }

    @NonNull
    public static Metadata parse(@NonNull String html) {
        final Map<String, String> meta = new HashMap<>();
        int from = 0;
        int tagStart;
        while ((tagStart = indexOfTag(html, "meta", from)) >= 0) {
            final Map<String, String> attributes = new HashMap<>();
            final int scanned = scanAttributes(html, tagStart + 5, attributes);
            if (scanned < 0) {
                // Broken or oversized tag: resume where the scan stopped, never before, so the work stays linear.
                from = Math.max(tagStart + 5, -scanned - 1);
                continue;
            }
            from = scanned;
            String key = attributes.get("property");
            if (isBlank(key)) {
                key = attributes.get("name");
            }
            final String content = attributes.get("content");
            if (isBlank(key) || content == null) {
                continue;
            }
            final String normalizedKey = key.trim().toLowerCase(Locale.ROOT);
            if (!meta.containsKey(normalizedKey)) {
                meta.put(normalizedKey, content);
            }
        }

        String title = firstNonBlank(meta, "og:title", "twitter:title");
        if (title == null) {
            final String documentTitle = documentTitle(html);
            if (!isBlank(documentTitle)) {
                title = documentTitle;
            }
        }
        final String description = firstNonBlank(meta, "og:description", "twitter:description", "description");
        final String imageUrl = firstNonBlank(meta,
            "og:image", "og:image:url", "og:image:secure_url", "twitter:image", "twitter:image:src");
        return new Metadata(title, description, imageUrl);
    }

    /**
     * Cut a size-capped HTML prefix back to its last complete tag, so a tag split by the cap is not half-parsed.
     */
    @NonNull
    public static String dropUnfinishedTag(@NonNull String htmlPrefix) {
        final int lastOpen = htmlPrefix.lastIndexOf('<');
        if (lastOpen >= 0 && htmlPrefix.indexOf('>', lastOpen) < 0) {
            return htmlPrefix.substring(0, lastOpen);
        }
        return htmlPrefix;
    }

    /**
     * True for a bot-protection interstitial (Cloudflare and similar). Such a page must never become a preview, even
     * though it has a title.
     */
    public static boolean isChallengePage(@NonNull String html, @Nullable String title) {
        if (title != null) {
            final String t = cleanText(title).toLowerCase(Locale.ROOT);
            if (t.startsWith("just a moment") || t.startsWith("attention required") || t.startsWith("checking your browser")
                || t.contains("ddos-guard")) {
                return true;
            }
        }
        return html.contains("/cdn-cgi/challenge-platform/") || html.contains("window._cf_chl_opt");
    }

    /** Trim, and strip leading/trailing bidi and zero-width marks. */
    @NonNull
    public static String cleanText(@NonNull String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isTrimmable(s.charAt(start))) {
            start++;
        }
        while (end > start && isTrimmable(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isTrimmable(char c) {
        return Character.isWhitespace(c) || c == '\u00A0' || INVISIBLE_MARKS.indexOf(c) >= 0;
    }

    /**
     * Index of the next {@code <name} tag start (case-insensitive, not a longer tag name such as {@code <metadata}) at
     * or after {@code from}, or -1.
     */
    private static int indexOfTag(@NonNull String html, @NonNull String name, int from) {
        int i = from;
        while ((i = html.indexOf('<', i)) >= 0) {
            final int afterName = i + 1 + name.length();
            if (html.regionMatches(true, i + 1, name, 0, name.length())
                && (afterName == html.length() || !isNameChar(html.charAt(afterName)))) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /**
     * Scan the attributes of a tag whose name ends just before {@code pos}, into {@code out} (first occurrence wins,
     * names lower-cased). Returns the index just past the closing {@code >}; for a tag that is unterminated, longer
     * than {@link #MAX_TAG_CHARS} or interrupted by a new {@code <} outside quotes, returns {@code -(stop + 1)} where
     * {@code stop} is where scanning ended. Every step moves forward.
     */
    private static int scanAttributes(@NonNull String html, int pos, @NonNull Map<String, String> out) {
        final int limit = (int) Math.min(html.length(), (long) pos + MAX_TAG_CHARS);
        while (true) {
            while (pos < limit && (Character.isWhitespace(html.charAt(pos)) || html.charAt(pos) == '/' || html.charAt(pos) == '=')) {
                pos++;
            }
            if (pos >= limit) {
                return -(pos + 1);
            }
            final char c = html.charAt(pos);
            if (c == '>') {
                return pos + 1;
            }
            if (c == '<') {
                return -(pos + 1);
            }
            if (c == '"' || c == '\'') {
                // Junk where a name should be: skip the quoted run and go on.
                final int close = indexOfBounded(html, c, pos + 1, limit);
                if (close < 0) {
                    return -(limit + 1);
                }
                pos = close + 1;
                continue;
            }
            final int nameStart = pos;
            while (pos < limit && isAttributeNameChar(html.charAt(pos))) {
                pos++;
            }
            final String name = html.substring(nameStart, pos).toLowerCase(Locale.ROOT);
            while (pos < limit && Character.isWhitespace(html.charAt(pos))) {
                pos++;
            }
            String value = "";
            if (pos < limit && html.charAt(pos) == '=') {
                pos++;
                while (pos < limit && Character.isWhitespace(html.charAt(pos))) {
                    pos++;
                }
                if (pos >= limit) {
                    return -(pos + 1);
                }
                final char quote = html.charAt(pos);
                if (quote == '"' || quote == '\'') {
                    final int close = indexOfBounded(html, quote, pos + 1, limit);
                    if (close < 0) {
                        return -(limit + 1);
                    }
                    value = html.substring(pos + 1, close);
                    pos = close + 1;
                } else {
                    final int valueStart = pos;
                    while (pos < limit && !Character.isWhitespace(html.charAt(pos))
                        && html.charAt(pos) != '>' && html.charAt(pos) != '<') {
                        pos++;
                    }
                    value = html.substring(valueStart, pos);
                }
            }
            if (!out.containsKey(name)) {
                out.put(name, value);
            }
        }
    }

    /** Index of {@code c} in {@code [from, limit)}, or -1; never looks past {@code limit}. */
    private static int indexOfBounded(@NonNull String html, char c, int from, int limit) {
        for (int i = from; i < limit; i++) {
            if (html.charAt(i) == c) {
                return i;
            }
        }
        return -1;
    }

    /** The text of the first {@code <title>...</title>}, or null. */
    @Nullable
    private static String documentTitle(@NonNull String html) {
        final int tagStart = indexOfTag(html, "title", 0);
        if (tagStart < 0) {
            return null;
        }
        final int contentStart = html.indexOf('>', tagStart);
        if (contentStart < 0) {
            return null;
        }
        final int contentEnd = indexOfClosingTag(html, "title", contentStart + 1);
        return contentEnd < 0 ? null : html.substring(contentStart + 1, contentEnd);
    }

    /** Index of the next {@code </name} (case-insensitive) at or after {@code from}, or -1. */
    private static int indexOfClosingTag(@NonNull String html, @NonNull String name, int from) {
        int i = from;
        while ((i = html.indexOf("</", i)) >= 0) {
            if (html.regionMatches(true, i + 2, name, 0, name.length())) {
                return i;
            }
            i += 2;
        }
        return -1;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ':' || c == '.';
    }

    private static boolean isAttributeNameChar(char c) {
        return !Character.isWhitespace(c) && c != '=' && c != '>' && c != '/' && c != '<' && c != '"' && c != '\'';
    }

    @Nullable
    private static String firstNonBlank(@NonNull Map<String, String> meta, @NonNull String... keys) {
        for (String key : keys) {
            final String value = meta.get(key);
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || cleanText(s).isEmpty();
    }
}
