package dev.cauce.evals.check;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Text normalization shared by the checks: case, spacing and, where asked, accents. */
final class Text {

    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private Text() {
    }

    /** Lower case, single spaces: what "contains, ignoring case and spacing" compares. */
    static String plain(String text) {
        return text == null ? "" : SPACES.matcher(text).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }

    /** {@link #plain} without accents: what menu detection compares ("caña" and "cana" alike). */
    static String folded(String text) {
        return MARKS.matcher(Normalizer.normalize(plain(text), Normalizer.Form.NFD)).replaceAll("");
    }
}
