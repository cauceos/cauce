package dev.cauce.api.webchat;

import dev.cauce.api.web.ErrorResponse;
import dev.cauce.channels.webchat.WebChatProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the web widget from the Cauce instance (ADR 0014, W4), at a versioned path a shop's tag pins,
 * {@code /web/widget/{version}/cauce-widget.js}. A version never changes once released, so it is cached
 * for a year as immutable; the tag carries its Subresource Integrity hash, which needs the script fetched
 * with CORS, hence {@code Access-Control-Allow-Origin: *}. The files come from
 * {@code cauce.web.widget-dir}, laid out by the widget's build ({@code dist/release/{version}/}); no
 * such directory, or no such version, is a 404. Outside API-key auth, like the rest of {@code /web/**}.
 */
@RestController
public class WidgetAssetController {

    static final String FILE = "cauce-widget.js";
    private static final Pattern VERSION = Pattern.compile("\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}");
    private static final Duration ONE_YEAR = Duration.ofDays(365);
    private static final MediaType JAVASCRIPT = MediaType.parseMediaType("text/javascript;charset=UTF-8");

    private final WebChatProperties properties;

    public WidgetAssetController(WebChatProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/web/widget/{version}/" + FILE)
    public ResponseEntity<?> widget(@PathVariable String version) {
        String dir = properties.getWidgetDir();
        if (dir == null || dir.isBlank() || !VERSION.matcher(version).matches()) {
            return notFound();
        }
        Path file = Path.of(dir).resolve(version).resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return notFound();
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            return notFound();
        }
        return ResponseEntity.ok()
                .contentType(JAVASCRIPT)
                .cacheControl(CacheControl.maxAge(ONE_YEAR).cachePublic().immutable())
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .header("X-Content-Type-Options", "nosniff")
                .body(bytes);
    }

    private static ResponseEntity<ErrorResponse> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of("not_found", "Not found"));
    }
}
