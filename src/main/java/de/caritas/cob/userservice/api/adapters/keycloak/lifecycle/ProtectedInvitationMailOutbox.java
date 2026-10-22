package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Durable protected mail; native approval and the actual invitation precede usable delivery. */
public final class ProtectedInvitationMailOutbox {
  public ProtectedInvitationMailOutbox(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      JdbcProtectedInvitationJournal journal,
      AccountLifecycleCodec json,
      byte[] contextKey,
      String realmId,
      Clock clock,
      KeycloakProtectedInvitations approvals,
      InviteMailDispatchService sender) {}

  public void enqueue(long inviteId, Supplier<Mail> prepareAndPersistToken) {}

  public InviteMailSendReceipt dispatch(long inviteId) {
    return null;
  }

  public record Mail(
      String rawToken,
      String acceptUrl,
      String subject,
      String body,
      long templateId,
      String language) {
    @Override
    public String toString() {
      return "ProtectedInvitationMail[redacted]";
    }
  }
}
