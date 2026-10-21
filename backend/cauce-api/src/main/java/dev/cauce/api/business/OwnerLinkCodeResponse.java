package dev.cauce.api.business;

import dev.cauce.business.owner.OwnerService.IssuedCode;
import java.time.Instant;
import java.util.UUID;

/**
 * A link code, returned once (ADR 0007, B8). The owner sends it to the owner bot before it expires;
 * on Telegram, the bot's deep link with {@code ?start=} and the code fills it in.
 */
public record OwnerLinkCodeResponse(String code, UUID channelConfigId, Instant expiresAt) {

    public static OwnerLinkCodeResponse from(IssuedCode issued) {
        return new OwnerLinkCodeResponse(issued.code(), issued.channelConfigId(), issued.expiresAt());
    }
}
