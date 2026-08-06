package de.caritas.cob.userservice.api.adapters.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.caritas.cob.userservice.api.model.identity.IdentitySession;

/** Public magic-link consume response preserving the existing token JSON contract. */
public record MagicLinkSessionResponseDTO(
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("expires_in") int expiresIn,
    @JsonProperty("refresh_expires_in") int refreshExpiresIn,
    @JsonProperty("refresh_token") String refreshToken,
    @JsonProperty("token_type") String tokenType,
    @JsonProperty("session_state") String sessionState,
    String scope) {

  public static MagicLinkSessionResponseDTO from(IdentitySession session) {
    return new MagicLinkSessionResponseDTO(
        session.accessToken(),
        session.expiresIn(),
        session.refreshExpiresIn(),
        session.refreshToken(),
        session.tokenType(),
        session.sessionState(),
        session.scope());
  }
}
