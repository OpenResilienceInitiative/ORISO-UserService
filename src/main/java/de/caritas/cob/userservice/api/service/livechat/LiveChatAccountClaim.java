package de.caritas.cob.userservice.api.service.livechat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Dedicated state avoids stale User updates overwriting a committed claim. */
@Entity
@Table(name = "live_chat_account_claim")
@Getter
@Setter
@NoArgsConstructor
public class LiveChatAccountClaim {
  @Id
  @Column(name = "user_id", length = 36)
  private String userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private LiveChatAccountLifecycle lifecycle;

  @Column(name = "intent_id", length = 36)
  private String intentId;

  @Column(name = "credential_verifier", length = 128)
  private String credentialVerifier;

  public LiveChatAccountClaim(String userId, LiveChatAccountLifecycle lifecycle) {
    this.userId = userId;
    this.lifecycle = lifecycle;
  }
}
