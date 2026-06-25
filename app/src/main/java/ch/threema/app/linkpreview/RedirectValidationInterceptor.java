package ch.threema.app.linkpreview;

import androidx.annotation.NonNull;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Response;

/**
 * F1Whisper: an OkHttp NETWORK interceptor that re-validates every redirect hop of a link-preview
 * fetch against {@link LinkPreviewValidator}. Because OkHttp invokes a network interceptor once per
 * hop, a 30x redirect to a non-https target, an internal IP, or a blocklisted host is aborted before
 * the request is actually sent there. This closes the open-redirect SSRF vector (a public URL that
 * 302s to {@code http://169.254.169.254/...} etc.). Ported from Signal's
 * {@code LinkPreviewRedirectValidationInterceptor}.
 */
public class RedirectValidationInterceptor implements Interceptor {

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        final String url = chain.request().url().toString();
        if (!LinkPreviewValidator.isValidPreviewUrl(url)) {
            chain.call().cancel();
            throw new IOException("Redirect target is not a valid preview URL");
        }
        return chain.proceed(chain.request());
    }
}
