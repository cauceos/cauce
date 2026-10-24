package dev.cauce.evals.voice;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spoken Spanish reduced to the words a transcription is compared by: lowercase, without accents
 * or punctuation, and every number — written in figures or spelt out — as its digits, one token
 * each. So "seis uno dos", "612" and "612-345" all agree digit by digit, and "cuatro" with "4":
 * a transcriber writing figures is not a mistake, and a phone number is compared the way it matters.
 *
 * <p>Spelt numbers are read up to the thousands ("trescientos cuarenta y cinco" is 345); a figure
 * spoken digit by digit ("seis uno dos") stays three numbers.
 *
 * <p>A clock time is one token, on the twelve-hour dial it is said on: "dos y media", "2:30",
 * "2.30", "14:30" and "14h30" are all {@code 2h30}; "nueve menos cuarto" is {@code 8h45}, "una en
 * punto" {@code 1h00}. Spoken, a time is an hour with "y media", "y cuarto", "menos cuarto" or "en
 * punto"; with "y" or "menos" and five, ten, twenty or twenty-five minutes only after "la" or "las"
 * ("las nueve y diez"), so "dos y cinco niños" stays numbers. Other fractions are not interpreted.
 */
public final class SpokenText {

    private static final Map<String, Integer> UNITS = Map.ofEntries(
            Map.entry("cero", 0), Map.entry("uno", 1), Map.entry("una", 1), Map.entry("un", 1),
            Map.entry("dos", 2), Map.entry("tres", 3), Map.entry("cuatro", 4), Map.entry("cinco", 5),
            Map.entry("seis", 6), Map.entry("siete", 7), Map.entry("ocho", 8), Map.entry("nueve", 9));

    /** Ten to twenty-nine: each a whole number below the hundreds, never followed by a unit. */
    private static final Map<String, Integer> TEENS = Map.ofEntries(
            Map.entry("diez", 10), Map.entry("once", 11), Map.entry("doce", 12), Map.entry("trece", 13),
            Map.entry("catorce", 14), Map.entry("quince", 15), Map.entry("dieciseis", 16),
            Map.entry("diecisiete", 17), Map.entry("dieciocho", 18), Map.entry("diecinueve", 19),
            Map.entry("veinte", 20), Map.entry("veintiuno", 21), Map.entry("veintiuna", 21),
            Map.entry("veintidos", 22), Map.entry("veintitres", 23), Map.entry("veinticuatro", 24),
            Map.entry("veinticinco", 25), Map.entry("veintiseis", 26), Map.entry("veintisiete", 27),
            Map.entry("veintiocho", 28), Map.entry("veintinueve", 29));

    private static final Map<String, Integer> TENS = Map.of("treinta", 30, "cuarenta", 40, "cincuenta", 50,
            "sesenta", 60, "setenta", 70, "ochenta", 80, "noventa", 90);

    private static final Map<String, Integer> HUNDREDS = Map.ofEntries(
            Map.entry("cien", 100), Map.entry("ciento", 100), Map.entry("doscientos", 200),
            Map.entry("doscientas", 200), Map.entry("trescientos", 300), Map.entry("trescientas", 300),
            Map.entry("cuatrocientos", 400), Map.entry("cuatrocientas", 400), Map.entry("quinientos", 500),
            Map.entry("quinientas", 500), Map.entry("seiscientos", 600), Map.entry("seiscientas", 600),
            Map.entry("setecientos", 700), Map.entry("setecientas", 700), Map.entry("ochocientos", 800),
            Map.entry("ochocientas", 800), Map.entry("novecientos", 900), Map.entry("novecientas", 900));

    /** The hours a time is said with, on the twelve-hour dial. */
    private static final Map<String, Integer> HOURS = Map.ofEntries(
            Map.entry("una", 1), Map.entry("uno", 1), Map.entry("dos", 2), Map.entry("tres", 3),
            Map.entry("cuatro", 4), Map.entry("cinco", 5), Map.entry("seis", 6), Map.entry("siete", 7),
            Map.entry("ocho", 8), Map.entry("nueve", 9), Map.entry("diez", 10), Map.entry("once", 11),
            Map.entry("doce", 12));

    private static final Map<String, Integer> MINUTES = Map.of("cinco", 5, "diez", 10, "veinte", 20,
            "veinticinco", 25);

    private static final String HOUR = "(una|uno|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce|\\d{1,2})";

    /** "2:30", "14.30", "14h30": a figure that is a time, not part of a longer number. */
    private static final Pattern FIGURE_TIME = Pattern.compile("(?<![\\d.,:])(\\d{1,2})[:.h](\\d{2})(?![\\d.,:]?\\d)");

    private static final Pattern SPOKEN_TIME = Pattern.compile("\\b" + HOUR
            + " (y media|y cuarto|menos cuarto|en punto)\\b");

    private static final Pattern SPOKEN_MINUTES = Pattern.compile("(?<=\\blas? )" + HOUR
            + " (y|menos) (cinco|diez|veinte|veinticinco)\\b");

    private SpokenText() {
    }

    /** {@code text} as the tokens it is compared by. */
    public static List<String> tokens(String text) {
        String unaccented = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String plain = times(unaccented)
                .replaceAll("[^a-z0-9ñ]+", " ")
                .strip();
        List<String> tokens = new ArrayList<>();
        if (plain.isEmpty()) {
            return tokens;
        }
        String[] words = plain.split(" ");
        NumberBuilder number = new NumberBuilder();
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.chars().allMatch(Character::isDigit)) {
                number.flushInto(tokens);
                digits(word, tokens);
            } else if ("y".equals(word) && number.expectsUnitAfterY() && i + 1 < words.length
                    && UNITS.containsKey(words[i + 1])) {
                continue; // "treinta y cinco": the "y" joins one number
            } else if (!number.accept(word)) {
                number.flushInto(tokens);
                if (!number.accept(word)) {
                    tokens.add(word);
                }
            }
        }
        number.flushInto(tokens);
        return tokens;
    }

    /** Every clock time in {@code text} as its one token, before the punctuation that marks it goes. */
    static String times(String text) {
        String result = replace(FIGURE_TIME, text, time -> {
            int hour = Integer.parseInt(time.group(1));
            int minutes = Integer.parseInt(time.group(2));
            return hour > 24 || minutes > 59 ? time.group() : clock(hour, minutes);
        });
        result = replace(SPOKEN_TIME, result, time -> {
            Integer hour = hour(time.group(1));
            if (hour == null) {
                return time.group();
            }
            return switch (time.group(2)) {
                case "y media" -> clock(hour, 30);
                case "y cuarto" -> clock(hour, 15);
                case "menos cuarto" -> clock(hour - 1, 45);
                default -> clock(hour, 0);
            };
        });
        return replace(SPOKEN_MINUTES, result, time -> {
            Integer hour = hour(time.group(1));
            int minutes = MINUTES.get(time.group(3));
            if (hour == null) {
                return time.group();
            }
            return "y".equals(time.group(2)) ? clock(hour, minutes) : clock(hour - 1, 60 - minutes);
        });
    }

    private static String replace(Pattern pattern, String text, java.util.function.Function<Matcher, String> by) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(by.apply(matcher)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static Integer hour(String said) {
        if (said.chars().allMatch(Character::isDigit)) {
            int hour = Integer.parseInt(said);
            return hour <= 24 ? hour : null;
        }
        return HOURS.get(said);
    }

    /** The time on the twelve-hour dial: 0 and 12 are 12, 14 is 2. */
    private static String clock(int hour, int minutes) {
        int dial = Math.floorMod(hour - 1, 12) + 1;
        return dial + "h" + (minutes < 10 ? "0" : "") + minutes;
    }

    private static void digits(String figure, List<String> tokens) {
        for (char digit : figure.toCharArray()) {
            tokens.add(String.valueOf(digit));
        }
    }

    /** A spelt number being read: thousands, hundreds, tens and units, each at most once. */
    private static final class NumberBuilder {

        private Integer thousands;
        private Integer hundreds;
        private Integer tens;
        private Integer units;
        private boolean teen;
        private boolean started;

        boolean expectsUnitAfterY() {
            return tens != null && units == null && !teen;
        }

        boolean accept(String word) {
            if ("mil".equals(word) && started && thousands == null) {
                thousands = Math.max(1, value());
                hundreds = null;
                tens = null;
                units = null;
                teen = false;
                return true;
            }
            if ("mil".equals(word) && !started) {
                started = true;
                thousands = 1;
                return true;
            }
            if (HUNDREDS.containsKey(word) && hundreds == null && tens == null && units == null && !teen) {
                hundreds = HUNDREDS.get(word);
                started = true;
                return true;
            }
            if (TENS.containsKey(word) && tens == null && units == null && !teen) {
                tens = TENS.get(word);
                started = true;
                return true;
            }
            if (TEENS.containsKey(word) && tens == null && units == null && !teen) {
                units = TEENS.get(word);
                teen = true;
                started = true;
                return true;
            }
            if (UNITS.containsKey(word) && units == null && !teen) {
                units = UNITS.get(word);
                started = true;
                return true;
            }
            return false;
        }

        private int value() {
            return (hundreds == null ? 0 : hundreds) + (tens == null ? 0 : tens) + (units == null ? 0 : units);
        }

        void flushInto(List<String> tokens) {
            if (!started) {
                return;
            }
            int number = (thousands == null ? 0 : thousands * 1000) + value();
            digits(String.valueOf(number), tokens);
            thousands = null;
            hundreds = null;
            tens = null;
            units = null;
            teen = false;
            started = false;
        }
    }
}
