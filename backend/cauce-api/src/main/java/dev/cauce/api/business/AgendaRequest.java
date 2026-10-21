package dev.cauce.api.business;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Body of {@code PUT /v1/tenants/{tenantId}/agenda} (ADR 0007, B6): the whole agenda, replacing
 * any previous one. Times are {@code HH:mm} in the business's time zone; days are {@code mon} …
 * {@code sun}. {@code holdMinutes} (default 10), {@code horizonDays} (default 60) and
 * {@code requirePhone} (default false) are optional. The domain validates the bounds.
 */
public record AgendaRequest(
        @NotBlank String unit,
        @NotNull Integer maxPartySize,
        Integer holdMinutes,
        Integer horizonDays,
        Boolean requirePhone,
        @NotEmpty List<@Valid @NotNull Shift> shifts,
        List<@NotNull LocalDate> closedDates) {

    public static final int DEFAULT_HOLD_MINUTES = 10;
    public static final int DEFAULT_HORIZON_DAYS = 60;

    /** One shift. */
    public record Shift(
            @NotBlank String id,
            @NotEmpty List<@NotBlank String> days,
            @NotNull LocalTime start,
            @NotNull LocalTime end,
            @NotNull Integer capacity) {
    }
}
