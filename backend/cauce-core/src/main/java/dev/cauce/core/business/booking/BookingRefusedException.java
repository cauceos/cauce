package dev.cauce.core.business.booking;

/** A booking action the agenda refused, with its {@link BookingRefusal}. */
public class BookingRefusedException extends RuntimeException {

    private final BookingRefusal refusal;

    public BookingRefusedException(BookingRefusal refusal) {
        super(refusal.reason() + ": " + refusal.message());
        this.refusal = refusal;
    }

    public BookingRefusal refusal() {
        return refusal;
    }
}
