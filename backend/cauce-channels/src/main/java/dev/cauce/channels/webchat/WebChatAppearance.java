package dev.cauce.channels.webchat;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How a web chat looks and speaks (ADR 0014, W4): the widget has no aesthetic of its own, and takes all
 * of it from here, so a shop's chat wears the shop's brand. The pairs a person reads (text on the
 * background, text on the primary colour) must reach WCAG AA contrast, 4.5:1, and the primary colour
 * against the background, which draws the launcher, the 3:1 of a component. {@code unavailableMessage}
 * and {@code contact} are what the widget shows when the service cannot answer, never a technical
 * error. {@code locale} picks the widget's fixed texts (the AI notice, the privacy line).
 */
public record WebChatAppearance(
        @JsonProperty("name") String name,
        @JsonProperty("avatar_url") String avatarUrl,
        @JsonProperty("primary_color") String primaryColor,
        @JsonProperty("on_primary_color") String onPrimaryColor,
        @JsonProperty("background_color") String backgroundColor,
        @JsonProperty("text_color") String textColor,
        @JsonProperty("font_family") String fontFamily,
        @JsonProperty("greeting") String greeting,
        @JsonProperty("placeholder") String placeholder,
        @JsonProperty("launcher_label") String launcherLabel,
        @JsonProperty("unavailable_message") String unavailableMessage,
        @JsonProperty("contact") String contact,
        @JsonProperty("locale") String locale) {

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Pattern FONT = Pattern.compile("[A-Za-z0-9 ,'\"\\-]{1,120}");
    private static final Set<String> LOCALES = Set.of("es", "en");
    public static final double TEXT_CONTRAST = 4.5;
    public static final double COMPONENT_CONTRAST = 3.0;

    /** The platform's neutral look, for a channel that sets none: system type, black on white. */
    public static final WebChatAppearance NEUTRAL = new WebChatAppearance("Asistente", null, "#1f2937", "#ffffff",
            "#ffffff", "#111827", "system-ui, sans-serif", null, null, null, null, null, "es");

    public WebChatAppearance {
        name = required(name, "name", 60);
        avatarUrl = optional(avatarUrl, "avatar_url", 500);
        if (avatarUrl != null && !avatarUrl.startsWith("https://")) {
            throw new IllegalArgumentException("avatar_url must be an https URL");
        }
        primaryColor = color(primaryColor, "primary_color");
        onPrimaryColor = color(onPrimaryColor, "on_primary_color");
        backgroundColor = color(backgroundColor, "background_color");
        textColor = color(textColor, "text_color");
        fontFamily = fontFamily == null || fontFamily.isBlank() ? "system-ui, sans-serif" : fontFamily.strip();
        if (!FONT.matcher(fontFamily).matches()) {
            throw new IllegalArgumentException("font_family is a CSS font list: letters, digits, spaces, commas, "
                    + "quotes and hyphens, up to 120 characters");
        }
        greeting = optional(greeting, "greeting", 300);
        placeholder = optional(placeholder, "placeholder", 80);
        launcherLabel = optional(launcherLabel, "launcher_label", 40);
        unavailableMessage = optional(unavailableMessage, "unavailable_message", 300);
        contact = optional(contact, "contact", 200);
        locale = locale == null || locale.isBlank() ? "es" : locale.strip().toLowerCase(Locale.ROOT);
        if (!LOCALES.contains(locale)) {
            throw new IllegalArgumentException("locale must be one of " + LOCALES);
        }
        requireContrast(textColor, backgroundColor, TEXT_CONTRAST, "text_color on background_color");
        requireContrast(onPrimaryColor, primaryColor, TEXT_CONTRAST, "on_primary_color on primary_color");
        requireContrast(primaryColor, backgroundColor, COMPONENT_CONTRAST, "primary_color against background_color");
    }

    /** The WCAG contrast ratio between two {@code #RRGGBB} colours. */
    public static double contrast(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        return 0.2126 * channel((rgb >> 16) & 0xff) + 0.7152 * channel((rgb >> 8) & 0xff) + 0.0722 * channel(rgb & 0xff);
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static void requireContrast(String fg, String bg, double min, String pair) {
        double ratio = contrast(fg, bg);
        if (ratio < min) {
            throw new IllegalArgumentException(pair + " has a contrast of " + String.format(Locale.ROOT, "%.2f", ratio)
                    + ":1, below the " + min + ":1 of WCAG AA");
        }
    }

    private static String color(String value, String field) {
        if (value == null || !HEX.matcher(value.strip()).matches()) {
            throw new IllegalArgumentException(field + " must be a #RRGGBB colour");
        }
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static String required(String value, String field, int max) {
        String stripped = optional(value, field, max);
        if (stripped == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return stripped;
    }

    private static String optional(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + " characters");
        }
        return stripped;
    }
}
