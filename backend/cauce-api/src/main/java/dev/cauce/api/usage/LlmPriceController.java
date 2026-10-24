package dev.cauce.api.usage;

import dev.cauce.api.web.PageParams;
import dev.cauce.api.web.PageResponse;
import dev.cauce.orchestration.usage.LlmPrice;
import dev.cauce.orchestration.usage.LlmPriceService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The price table (ADR 0010, S1). An operator adds prices; any tenant lists the prices of its
 * hierarchy, which are the ones its usage is priced with. There is no update or delete: a new
 * price is a new row, in force from the moment it is added.
 */
@RestController
@RequestMapping("/v1/llm-prices")
public class LlmPriceController {

    private final LlmPriceService priceService;

    public LlmPriceController(LlmPriceService priceService) {
        this.priceService = priceService;
    }

    @PostMapping
    public ResponseEntity<LlmPriceResponse> add(@Valid @RequestBody AddLlmPriceRequest request) {
        LlmPrice price = priceService.addPrice(request.provider(), request.model(), request.inputUsdPerMtok(),
                request.outputUsdPerMtok(), request.cacheReadUsdPerMtok(), request.cacheWriteUsdPerMtok(),
                request.cacheWriteLongUsdPerMtok(), request.audioUsdPerMinute());
        return ResponseEntity.status(HttpStatus.CREATED).body(LlmPriceResponse.from(price));
    }

    @GetMapping
    public PageResponse<LlmPriceResponse> list(@RequestParam(defaultValue = "50") int limit,
                                               @RequestParam(required = false) String cursor) {
        PageParams page = PageParams.parse(limit, cursor);
        List<LlmPriceResponse> fetched = priceService.listPrices(page.afterId(), page.fetchSize()).stream()
                .map(LlmPriceResponse::from).toList();
        return PageResponse.of(fetched, page.limit(), LlmPriceResponse::id);
    }
}
