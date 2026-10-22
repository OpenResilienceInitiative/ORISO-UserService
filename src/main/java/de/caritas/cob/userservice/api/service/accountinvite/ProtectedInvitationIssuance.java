package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationJournal;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.KeycloakProtectedInvitations;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationMailOutbox;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import jakarta.persistence.EntityManager;
import java.util.function.BiFunction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Public issuance orchestration. Inert receiving signature until its public-service RED gate. */
public final class ProtectedInvitationIssuance {
  public ProtectedInvitationIssuance(
      AccountInviteRepository invites,
      InviteEmailTemplateRepository templates,
      InviteEmailDeliveryRepository deliveries,
      PlatformTransactionManager transactions,
      JdbcTemplate jdbc,
      EntityManager entityManager,
      AuthenticatedUser human,
      TaskActorConfiguration actors,
      ProtectedInvitationProfiles profiles,
      AccountLifecycleCodec json,
      ProtectedInvitationCodec codec,
      JdbcProtectedInvitationJournal journal,
      KeycloakProtectedInvitations approvals,
      ProtectedInvitationMailOutbox outbox) {}

  public AccountInviteService.InviteSendResult send(
      AccountInvite invite,
      InviteEmailTemplate template,
      BiFunction<AccountInvite, InviteEmailTemplate, ProtectedInvitationMailOutbox.Mail> prepare) {
    return null;
  }
}
