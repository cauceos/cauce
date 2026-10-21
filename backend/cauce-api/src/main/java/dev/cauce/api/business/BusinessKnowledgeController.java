package dev.cauce.api.business;

import dev.cauce.api.web.PageParams;
import dev.cauce.api.web.PageResponse;
import dev.cauce.business.catalog.CatalogService;
import dev.cauce.business.gap.KnowledgeGapService;
import dev.cauce.business.profile.BusinessProfileService;
import dev.cauce.core.business.catalog.CatalogItem;
import dev.cauce.core.business.catalog.CatalogNotFoundException;
import dev.cauce.core.business.profile.BusinessProfile;
import dev.cauce.core.business.profile.BusinessProfile.Fact;
import dev.cauce.core.business.profile.BusinessProfileNotFoundException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a business tells its agents (ADR 0007, B4): the profile and the catalog, each loaded and
 * replaced as a whole, and the questions the agents could not answer from them. A business is a
 * CLIENT tenant ({@code 422 invalid_tenant_tier} otherwise); one outside the caller's scope is a
 * 404, indistinguishable from one that does not exist.
 */
@RestController
public class BusinessKnowledgeController {

    private final BusinessProfileService profileService;
    private final CatalogService catalogService;
    private final KnowledgeGapService gapService;

    public BusinessKnowledgeController(BusinessProfileService profileService, CatalogService catalogService,
                                       KnowledgeGapService gapService) {
        this.profileService = profileService;
        this.catalogService = catalogService;
        this.gapService = gapService;
    }

    @PutMapping("/v1/tenants/{tenantId}/business-profile")
    public BusinessProfileResponse putProfile(@PathVariable UUID tenantId,
                                              @Valid @RequestBody BusinessProfileRequest request) {
        List<Fact> facts = request.facts().entrySet().stream()
                .map(entry -> new Fact(entry.getKey(), entry.getValue()))
                .toList();
        BusinessProfile.DigestSchedule digest = request.dailyDigestTime() == null ? null
                : new BusinessProfile.DigestSchedule(request.dailyDigestTime(),
                        Boolean.TRUE.equals(request.dailyDigestSkipWhenEmpty()));
        return BusinessProfileResponse.from(profileService.putProfile(tenantId, request.name(), request.timeZone(),
                request.locale(), facts, request.handoffCategories() == null ? List.of() : request.handoffCategories(),
                digest));
    }

    @GetMapping("/v1/tenants/{tenantId}/business-profile")
    public BusinessProfileResponse getProfile(@PathVariable UUID tenantId) {
        return BusinessProfileResponse.from(profileService.findProfile(tenantId).orElseThrow(() ->
                new BusinessProfileNotFoundException("No business profile found for tenant " + tenantId)));
    }

    @PutMapping("/v1/tenants/{tenantId}/catalog")
    public CatalogResponse putCatalog(@PathVariable UUID tenantId, @Valid @RequestBody CatalogRequest request) {
        List<CatalogItem> items = request.items().stream()
                .map(item -> new CatalogItem(item.ref(), item.section(), item.name(), item.description(),
                        item.price(), item.attributes()))
                .toList();
        return CatalogResponse.from(catalogService.putCatalog(tenantId,
                request.declaredAttributes() == null ? List.of() : request.declaredAttributes(), items));
    }

    @GetMapping("/v1/tenants/{tenantId}/catalog")
    public CatalogResponse getCatalog(@PathVariable UUID tenantId) {
        return CatalogResponse.from(catalogService.findCatalog(tenantId).orElseThrow(() ->
                new CatalogNotFoundException("No catalog found for tenant " + tenantId)));
    }

    @GetMapping("/v1/tenants/{tenantId}/unanswered-questions")
    public PageResponse<UnansweredQuestionResponse> listUnansweredQuestions(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {
        PageParams page = PageParams.parse(limit, cursor);
        List<UnansweredQuestionResponse> fetched = gapService
                .listForTenant(tenantId, page.afterId(), page.fetchSize())
                .stream().map(UnansweredQuestionResponse::from).toList();
        return PageResponse.of(fetched, page.limit(), UnansweredQuestionResponse::id);
    }
}
