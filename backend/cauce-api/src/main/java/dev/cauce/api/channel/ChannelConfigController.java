package dev.cauce.api.channel;

import dev.cauce.channels.ChannelConfigCreationResult;
import dev.cauce.channels.ChannelConfigService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for channel bindings. Thin: delegates to {@link ChannelConfigService};
 * tenant context is derived from the validated API key, and RLS enforces that the agent is
 * visible to the caller (out-of-scope agents surface as not-found).
 *
 * <p>Listing and disabling bindings are deferred until the operational need exists (TODO,
 * same criterion as pagination).
 */
@RestController
public class ChannelConfigController {

    private final ChannelConfigService channelConfigService;

    public ChannelConfigController(ChannelConfigService channelConfigService) {
        this.channelConfigService = channelConfigService;
    }

    @PostMapping("/v1/agents/{agentId}/channels")
    public ResponseEntity<ChannelConfigResponse> create(
            @PathVariable UUID agentId,
            @Valid @RequestBody CreateChannelConfigRequest request) {
        ChannelConfigCreationResult result = channelConfigService.createChannelConfig(
                agentId, request.channelType(), request.credential());
        return ResponseEntity.status(HttpStatus.CREATED).body(ChannelConfigResponse.from(result));
    }
}
