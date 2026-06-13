package dev.cauce.tools;

import dev.cauce.tools.clock.ClockTool;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the built-in tools as Spring beans. Discovered by the application's
 * {@code dev.cauce} component scan; the {@link dev.cauce.tools.spi.ToolRegistry} then collects
 * them. Adding another built-in tool is a new {@code @Bean} method here.
 */
@Configuration
public class ToolsConfiguration {

    /** The built-in clock tool, driven by the system UTC clock in production. */
    @Bean
    public ClockTool clockTool() {
        return new ClockTool(Clock.systemUTC());
    }
}
