package dev.cauce.api.business;

import dev.cauce.api.web.PageParams;
import dev.cauce.api.web.PageResponse;
import dev.cauce.business.handoff.HandoffService;
import dev.cauce.business.owner.OwnerNotLinkedException;
import dev.cauce.business.owner.OwnerNotificationService;
import dev.cauce.business.owner.OwnerService;
import dev.cauce.core.business.handoff.Handoff;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The owner of a business (ADR 0007, B7, B8, B9): the link code that binds their chat on the owner
 * bot, the cases agents handed to them, and the notices sent, or not sent and why. The records are
 * the source of truth: a notice that never arrived loses nothing here.
 */
@RestController
public class OwnerController {

    private final OwnerService ownerService;
    private final HandoffService handoffService;
    private final OwnerNotificationService notificationService;

    public OwnerController(OwnerService ownerService, HandoffService handoffService,
                           OwnerNotificationService notificationService) {
        this.ownerService = ownerService;
        this.handoffService = handoffService;
        this.notificationService = notificationService;
    }

    /** Issues a single-use code for the owner bot {@code channel_config_id}, returned once. */
    @PostMapping("/v1/tenants/{tenantId}/owner-link-codes")
    public ResponseEntity<OwnerLinkCodeResponse> issueLinkCode(@PathVariable UUID tenantId,
                                                               @Valid @RequestBody OwnerLinkCodeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(OwnerLinkCodeResponse.from(
                ownerService.issueLinkCode(tenantId, request.channelConfigId())));
    }

    @GetMapping("/v1/tenants/{tenantId}/owner")
    public OwnerResponse getOwner(@PathVariable UUID tenantId) {
        return OwnerResponse.from(ownerService.findOwner(tenantId).orElseThrow(() ->
                new OwnerNotLinkedException("No owner is linked to tenant " + tenantId)));
    }

    @GetMapping("/v1/tenants/{tenantId}/handoffs")
    public PageResponse<HandoffResponse> listHandoffs(@PathVariable UUID tenantId,
                                                      @RequestParam(required = false) Handoff.Status status,
                                                      @RequestParam(defaultValue = "50") int limit,
                                                      @RequestParam(required = false) String cursor) {
        PageParams page = PageParams.parse(limit, cursor);
        List<HandoffResponse> fetched = handoffService.listForTenant(tenantId, status, page.afterId(), page.fetchSize())
                .stream().map(HandoffResponse::from).toList();
        return PageResponse.of(fetched, page.limit(), HandoffResponse::id);
    }

    @PostMapping("/v1/handoffs/{handoffId}/resolve")
    public HandoffResponse resolve(@PathVariable UUID handoffId) {
        return HandoffResponse.from(handoffService.resolve(handoffId));
    }

    @GetMapping("/v1/tenants/{tenantId}/owner-notifications")
    public PageResponse<OwnerNotificationResponse> listNotifications(@PathVariable UUID tenantId,
                                                                     @RequestParam(defaultValue = "50") int limit,
                                                                     @RequestParam(required = false) String cursor) {
        PageParams page = PageParams.parse(limit, cursor);
        List<OwnerNotificationResponse> fetched = notificationService
                .listForTenant(tenantId, page.afterId(), page.fetchSize())
                .stream().map(OwnerNotificationResponse::from).toList();
        return PageResponse.of(fetched, page.limit(), OwnerNotificationResponse::id);
    }
}
