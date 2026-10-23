package dev.cauce.api.usage;

import dev.cauce.orchestration.usage.SpendLimitService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The monthly spend limit of a client (ADR 0010, S5, S6). The client and its ancestors read it with
 * what was spent this month (UTC); only a strict ancestor sets or removes it — the client itself
 * gets a 403 {@code spend_limit_set_by_ancestor}, a partner or operator a 422 for a tenant that is
 * not a CLIENT.
 */
@RestController
@RequestMapping("/v1/tenants/{id}/spend-limit")
public class SpendLimitController {

    private final SpendLimitService spendLimitService;

    public SpendLimitController(SpendLimitService spendLimitService) {
        this.spendLimitService = spendLimitService;
    }

    @GetMapping
    public SpendLimitResponse get(@PathVariable UUID id) {
        return SpendLimitResponse.from(spendLimitService.status(id));
    }

    @PutMapping
    public SpendLimitResponse set(@PathVariable UUID id, @Valid @RequestBody SetSpendLimitRequest request) {
        return SpendLimitResponse.from(spendLimitService.setLimit(id, request.monthlyLimitUsd()));
    }

    @DeleteMapping
    public ResponseEntity<Void> remove(@PathVariable UUID id) {
        spendLimitService.removeLimit(id);
        return ResponseEntity.noContent().build();
    }
}
