package dev.cauce.channels.spi;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the text of an outbound message for what a channel can show. Agents write the Markdown
 * models write ({@code **bold**}, {@code *italic*}, {@code `code`}, {@code [text](url)}, {@code #}
 * headings, {@code *} bullets); a channel either shows those as formatting or must not show the
 * markers at all, so every outbound adapter renders {@link ChannelOutboundMessage#content()}
 * through one of these before sending it, never verbatim:
 * <ul>
 *   <li>{@link #toPlain} — no formatting: the markers are dropped, a link becomes {@code text (url)};</li>
 *   <li>{@link #toHtml} — the HTML subset of Telegram's {@code parse_mode=HTML} ({@code b}, {@code i},
 *       {@code s}, {@code code}, {@code pre}, {@code a}), everything else escaped;</li>
 *   <li>{@link #toWhatsApp} — WhatsApp's own markers ({@code *bold*}, {@code _italic_}, {@code ~strike~},
 *       {@code `code`}, {@code ```block```}).</li>
 * </ul>
 * Conservative on purpose: a marker counts only when it opens and closes around text, so a lone
 * asterisk ({@code 5 * 3}) or a {@code snake_case} word stays as written. A stray {@code **} is never
 * meaningful prose and is dropped.
 */
public final class ReplyMarkdown {

    private static final Pattern FENCE = Pattern.compile("(?s)```[^\\n`]*\\n?(.*?)```");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[*+]\\s+");
    private static final Pattern INLINE = Pattern.compile(String.join("|",
            "`([^`\\n]+)`",
            "\\[([^\\]\\n]+)\\]\\((https?://[^)\\s]+)\\)",
            "\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*",
            "__(?=\\S)(.+?)(?<=\\S)__",
            "~~(?=\\S)(.+?)(?<=\\S)~~",
            "(?<![\\w*])\\*(?=[^\\s*])([^*\\n]+?)(?<=[^\\s*])\\*(?![\\w*])",
            "(?<![\\w_])_(?=[^\\s_])([^_\\n]+?)(?<=[^\\s_])_(?![\\w_])"));

    private ReplyMarkdown() {
    }

    /** {@code markdown} as plain text: what a channel without formatting shows. */
    public static String toPlain(String markdown) {
        return render(markdown, PLAIN);
    }

    /** {@code markdown} as the HTML subset Telegram's {@code parse_mode=HTML} accepts. */
    public static String toHtml(String markdown) {
        return render(markdown, HTML);
    }

    /** {@code markdown} in WhatsApp's formatting markers. */
    public static String toWhatsApp(String markdown) {
        return render(markdown, WHATSAPP);
    }

    /** How one channel shows each piece. */
    private interface Style {

        String text(String text);

        String bold(String inner);

        String italic(String inner);

        String strike(String inner);

        String code(String code);

        String block(String code);

        String link(String text, String url);
    }

    private static final Style PLAIN = new Style() {
        @Override
        public String text(String text) {
            return text.replace("**", "").replace("__", "");
        }

        @Override
        public String bold(String inner) {
            return inner;
        }

        @Override
        public String italic(String inner) {
            return inner;
        }

        @Override
        public String strike(String inner) {
            return inner;
        }

        @Override
        public String code(String code) {
            return code;
        }

        @Override
        public String block(String code) {
            return code;
        }

        @Override
        public String link(String text, String url) {
            return text.equals(url) ? url : text + " (" + url + ")";
        }
    };

    private static final Style HTML = new Style() {
        @Override
        public String text(String text) {
            return escape(PLAIN.text(text));
        }

        @Override
        public String bold(String inner) {
            return "<b>" + inner + "</b>";
        }

        @Override
        public String italic(String inner) {
            return "<i>" + inner + "</i>";
        }

        @Override
        public String strike(String inner) {
            return "<s>" + inner + "</s>";
        }

        @Override
        public String code(String code) {
            return "<code>" + escape(code) + "</code>";
        }

        @Override
        public String block(String code) {
            return "<pre>" + escape(code) + "</pre>";
        }

        @Override
        public String link(String text, String url) {
            return "<a href=\"" + escape(url).replace("\"", "&quot;") + "\">" + text + "</a>";
        }
    };

    private static final Style WHATSAPP = new Style() {
        @Override
        public String text(String text) {
            return PLAIN.text(text);
        }

        @Override
        public String bold(String inner) {
            return "*" + inner + "*";
        }

        @Override
        public String italic(String inner) {
            return "_" + inner + "_";
        }

        @Override
        public String strike(String inner) {
            return "~" + inner + "~";
        }

        @Override
        public String code(String code) {
            return "`" + code + "`";
        }

        @Override
        public String block(String code) {
            return "```" + code + "```";
        }

        @Override
        public String link(String text, String url) {
            return PLAIN.link(text, url);
        }
    };

    private static String render(String markdown, Style style) {
        StringBuilder out = new StringBuilder();
        Matcher fence = FENCE.matcher(markdown);
        int at = 0;
        while (fence.find()) {
            out.append(lines(markdown.substring(at, fence.start()), style));
            out.append(style.block(stripTrailingNewline(fence.group(1))));
            at = fence.end();
        }
        out.append(lines(markdown.substring(at), style));
        return out.toString();
    }

    private static String lines(String text, Style style) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = BULLET.matcher(lines[i]).replaceFirst("$1- ");
            Matcher heading = HEADING.matcher(line);
            out.append(heading.matches() ? style.bold(inline(heading.group(1), style)) : inline(line, style));
        }
        return out.toString();
    }

    private static String inline(String text, Style style) {
        StringBuilder out = new StringBuilder();
        Matcher m = INLINE.matcher(text);
        int at = 0;
        while (m.find()) {
            out.append(style.text(text.substring(at, m.start())));
            if (m.group(1) != null) {
                out.append(style.code(m.group(1)));
            } else if (m.group(2) != null) {
                out.append(style.link(inline(m.group(2), style), m.group(3)));
            } else if (m.group(4) != null) {
                out.append(style.bold(inline(m.group(4), style)));
            } else if (m.group(5) != null) {
                out.append(style.bold(inline(m.group(5), style)));
            } else if (m.group(6) != null) {
                out.append(style.strike(inline(m.group(6), style)));
            } else if (m.group(7) != null) {
                out.append(style.italic(inline(m.group(7), style)));
            } else {
                out.append(style.italic(inline(m.group(8), style)));
            }
            at = m.end();
        }
        out.append(style.text(text.substring(at)));
        return out.toString();
    }

    private static String stripTrailingNewline(String code) {
        return code.endsWith("\n") ? code.substring(0, code.length() - 1) : code;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
