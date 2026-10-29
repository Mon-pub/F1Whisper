package ch.threema.app.linkpreview;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

/**
 * F1Whisper: security gate for sender-side link previews, ported from Signal's
 * {@code LinkUtil.isValidPreviewUrl}. A URL must pass this BOTH before it is fetched and on every
 * HTTP redirect hop (see {@link RedirectValidationInterceptor}). The same predicate is also run on
 * the receiving side so a malicious sender cannot inject a card for a spoofed/dangerous URL.
 *
 * <p>Checks (all must hold):
 * <ul>
 *     <li>parses as a URI and as an OkHttp {@link HttpUrl}</li>
 *     <li>scheme is exactly {@code https} (no plain http)</li>
 *     <li>no Unicode directional-override / box-drawing characters (anti-spoof)</li>
 *     <li>host is not a homograph mix (must be all-ASCII or all-non-ASCII)</li>
 *     <li>host is not in the blocklist (localhost, onion, i2p, invalid, test, example.*)</li>
 *     <li>host, if an IP literal, is not private/loopback/link-local/ULA/multicast (anti-SSRF)</li>
 * </ul>
 *
 * <p>Note: domain names are intentionally NOT resolved here (no DNS lookup), matching Signal — this
 * avoids leaking a lookup and side effects; DNS-rebinding is a known, accepted gap mitigated by the
 * per-redirect re-validation and the blocklist.
 */
public final class LinkPreviewValidator {

    private LinkPreviewValidator() {
    }

    // Unicode directional overrides (U+202A..U+202E, U+2066..U+2069) + box-drawing (U+2500..U+25FF).
    private static final Pattern ILLEGAL_CHARACTERS_PATTERN = Pattern.compile(
        "[\\u202A-\\u202E\\u2066-\\u2069\\u2500-\\u25FF]");

    // Consecutive dots or the Unicode ellipsis are not legal in a real host.
    private static final Pattern ILLEGAL_PERIODS_PATTERN = Pattern.compile("([.\\u2026]){2,}");

    private static final Pattern ALL_ASCII_PATTERN = Pattern.compile("^[\\x00-\\x7F]*$");
    private static final Pattern ALL_NON_ASCII_PATTERN = Pattern.compile("^[^\\x00-\\x7F]*$");

    private static final Pattern INVALID_DOMAINS_PATTERN = Pattern.compile(
        "^(.*\\.)?(example|example\\.com|example\\.net|example\\.org|i2p|invalid|localhost|onion|test)$",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern IPV4_LITERAL_PATTERN = Pattern.compile(
        "^(\\d{1,3})(\\.\\d{1,3}){3}$");

    /**
     * @return {@code true} if {@code url} is safe to fetch / render a preview for.
     */
    public static boolean isValidPreviewUrl(@Nullable String url) {
        if (url == null) {
            return false;
        }

        if (ILLEGAL_CHARACTERS_PATTERN.matcher(url).find()) {
            return false;
        }

        final URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return false;
        }

        final HttpUrl httpUrl = HttpUrl.parse(url);
        if (httpUrl == null) {
            return false;
        }

        if (!"https".equals(httpUrl.scheme())) {
            return false;
        }

        final String host = httpUrl.host();
        if (host.isEmpty()) {
            return false;
        }

        if (ILLEGAL_PERIODS_PATTERN.matcher(host).find()) {
            return false;
        }

        // Homograph defense: a host mixing ASCII and non-ASCII (after removing the dots) is the
        // classic spoofing surface (e.g. Cyrillic "а" among Latin letters) -> reject it.
        final String hostNoDots = host.replace(".", "");
        final boolean allAscii = ALL_ASCII_PATTERN.matcher(hostNoDots).matches();
        final boolean allNonAscii = ALL_NON_ASCII_PATTERN.matcher(hostNoDots).matches();
        if (!allAscii && !allNonAscii) {
            return false;
        }

        if (INVALID_DOMAINS_PATTERN.matcher(host).matches()) {
            return false;
        }

        if (isPrivateOrLocalHost(host)) {
            return false;
        }

        return true;
    }

    /**
     * @return {@code true} if {@code host} is an IP literal pointing at a private / loopback /
     * link-local / unique-local / multicast / any-local address (anti-SSRF). Hostnames that are not
     * IP literals are not resolved and return {@code false} here (covered by the blocklist instead).
     */
    private static boolean isPrivateOrLocalHost(@NonNull String host) {
        String candidate = host;
        // OkHttp keeps IPv6 hosts bracketed in some contexts; strip for InetAddress parsing.
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }

        final boolean looksLikeIpv4 = IPV4_LITERAL_PATTERN.matcher(candidate).matches();
        final boolean looksLikeIpv6 = candidate.indexOf(':') >= 0;
        if (!looksLikeIpv4 && !looksLikeIpv6) {
            // A real hostname; do NOT perform DNS resolution here.
            return false;
        }

        final InetAddress address;
        try {
            // Safe: getByName on an IP literal parses it without a DNS lookup.
            address = InetAddress.getByName(candidate);
        } catch (Exception e) {
            // Could not parse as a literal -> treat as a hostname (not blocked here).
            return false;
        }

        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return true;
        }

        // IPv6 unique local addresses (fc00::/7) are not covered by the helpers above.
        if (address instanceof Inet6Address) {
            final byte[] bytes = address.getAddress();
            return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        }

        // Extra IPv4 ranges not flagged as site-local by the JDK: CGNAT 100.64.0.0/10.
        if (address instanceof Inet4Address) {
            final byte[] bytes = address.getAddress();
            final int b0 = bytes[0] & 0xff;
            final int b1 = bytes[1] & 0xff;
            return b0 == 100 && b1 >= 64 && b1 <= 127;
        }

        return false;
    }
}
