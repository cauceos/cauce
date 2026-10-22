package dev.cauce.evals.check;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * What one turn left behind, as the runner read it from the records (ADR 0009, E4): the person's
 * message, the tools called with their input and output in order, the reply, what the channel
 * delivered, the latest notice to the owner, and the business's records after the turn.
 *
 * @param delivered the reply as the scenario's channel renders it
 * @param latestNotice the content of the most recent owner notification, or null
 */
public record TurnTrace(String message, List<ToolUse> tools, String reply, String delivered, String latestNotice,
                        ObservedState state) {

    public TurnTrace {
        tools = List.copyOf(tools);
    }

    /** One tool call of the turn. */
    public record ToolUse(String name, Map<String, Object> input, String output) {
    }

    /** The business's records after the turn, in the vocabulary of the expected state. */
    public record ObservedState(List<ObservedBooking> bookings, List<ObservedCase> cases, int questions,
                                List<ObservedNotice> notices, int digests, String latestDigest) {

        public ObservedState {
            bookings = List.copyOf(bookings);
            cases = List.copyOf(cases);
            notices = List.copyOf(notices);
        }
    }

    /**
     * A booking row. {@code status} is {@code HELD} only while its hold lasts; a lapsed hold is
     * {@code HELD_EXPIRED}, which holds nothing and no expected state names.
     */
    public record ObservedBooking(String status, LocalDate date, String shift, String time, int partySize, String code,
                                  String contactName) {
    }

    /** A handoff row. */
    public record ObservedCase(String category, String status) {
    }

    /** An owner notification row. */
    public record ObservedNotice(String kind, String status, String reason, String content) {
    }
}
