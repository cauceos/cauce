package dev.cauce.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The web widget served by the instance (ADR 0014, W4): at its versioned path, without an API key,
 * cached for a year as immutable, fetchable with CORS for its integrity check; a version that is not
 * there, or a path that is not a version, is the API's 404.
 */
class WidgetAssetApiIT extends AbstractBusinessApiIT {

    private static final Path DIR = release();

    private static Path release() {
        try {
            Path dir = Files.createTempDirectory("cauce-widget");
            Files.createDirectories(dir.resolve("0.1.0"));
            Files.writeString(dir.resolve("0.1.0").resolve("cauce-widget.js"), "(()=>{/* widget */})();");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void widgetDir(DynamicPropertyRegistry registry) {
        registry.add("cauce.web.widget-dir", DIR::toString);
    }

    @Test
    void aReleasedVersion_isServedWithoutAKey_immutableForAYear_andFetchableWithCors() throws Exception {
        mockMvc.perform(get("/web/widget/0.1.0/cauce-widget.js").header(HttpHeaders.ORIGIN, "https://caucewebs.es"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/javascript;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=31536000, public, immutable"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().string("(()=>{/* widget */})();"));
    }

    @Test
    void aVersionNotReleased_orAPathThatIsNoVersion_isTheApisNotFound() throws Exception {
        mockMvc.perform(get("/web/widget/9.9.9/cauce-widget.js"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
        // A traversal never reaches a file: refused before the controller, or as no version.
        mockMvc.perform(get("/web/widget/..%2F..%2Fetc/cauce-widget.js"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/web/widget/latest/cauce-widget.js"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
    }
}
