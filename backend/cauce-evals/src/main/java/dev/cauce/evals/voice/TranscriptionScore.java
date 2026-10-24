package dev.cauce.evals.voice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a transcription compares with what the note says (ADR 0012): the word error rate — the
 * substitutions, deletions and insertions that turn the expected words into the heard ones, over
 * the expected words — and, for each critical datum, whether its words were heard together and in
 * order. Both on {@link SpokenText} tokens.
 *
 * @param wordErrorRate 0 when every word was heard; above 1 when the transcription added more than
 *        the note said
 * @param expectedWords how many words the note says, the rate's denominator
 * @param criticalData each critical datum of the note, as said, and whether it was heard right
 */
public record TranscriptionScore(double wordErrorRate, int expectedWords, Map<String, Boolean> criticalData) {

    public TranscriptionScore {
        criticalData = new LinkedHashMap<>(criticalData);
    }

    /** {@code heard} scored against {@code expected} and its {@code criticalData}. */
    public static TranscriptionScore of(String expected, String heard, List<String> criticalData) {
        List<String> reference = SpokenText.tokens(expected);
        List<String> hypothesis = SpokenText.tokens(heard);
        Map<String, Boolean> data = new LinkedHashMap<>();
        criticalData.forEach(datum -> data.put(datum, containsInOrder(hypothesis, SpokenText.tokens(datum))));
        double rate = reference.isEmpty() ? (hypothesis.isEmpty() ? 0 : 1)
                : (double) editDistance(reference, hypothesis) / reference.size();
        return new TranscriptionScore(rate, reference.size(), data);
    }

    /** Whether every critical datum of the note was heard right (true for a note naming none). */
    public boolean criticalDataRight() {
        return criticalData.values().stream().allMatch(Boolean::booleanValue);
    }

    static int editDistance(List<String> reference, List<String> hypothesis) {
        int[] previous = new int[hypothesis.size() + 1];
        int[] current = new int[hypothesis.size() + 1];
        for (int j = 0; j <= hypothesis.size(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= reference.size(); i++) {
            current[0] = i;
            for (int j = 1; j <= hypothesis.size(); j++) {
                int substitution = previous[j - 1] + (reference.get(i - 1).equals(hypothesis.get(j - 1)) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[hypothesis.size()];
    }

    private static boolean containsInOrder(List<String> heard, List<String> datum) {
        if (datum.isEmpty()) {
            return true;
        }
        for (int start = 0; start + datum.size() <= heard.size(); start++) {
            if (heard.subList(start, start + datum.size()).equals(datum)) {
                return true;
            }
        }
        return false;
    }
}
