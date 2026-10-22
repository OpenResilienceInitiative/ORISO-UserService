package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationJournal;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.KeycloakProtectedInvitations;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationMailOutbox;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.FrozenApproval;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.VerifiedApproval;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.function.Supplier;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import java.util.function.BiFunction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.access.AccessDeniedException;

/** The immutable proposal is durable before the immediate native human approval RPC. */
public final class ProtectedInvitationIssuance {
  private static final String REDACTED_SUBJECT = "Protected invitation sent";
  private final AccountInviteRepository invites;
  private final InviteEmailTemplateRepository templates;
  private final InviteEmailDeliveryRepository deliveries;
  private final JdbcTemplate jdbc;
  private final EntityManager entityManager;
  private final AuthenticatedUser human;
  private final TaskActorConfiguration actors;
  private final ProtectedInvitationProfiles profiles;
  private final AccountLifecycleCodec json;
  private final ProtectedInvitationCodec codec;
  private final JdbcProtectedInvitationJournal journal;
  private final KeycloakProtectedInvitations approvals;
  private final ProtectedInvitationMailOutbox outbox;
  private final TransactionTemplate local;

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
      ProtectedInvitationMailOutbox outbox) {
    this.invites = Objects.requireNonNull(invites);
    this.templates = Objects.requireNonNull(templates);
    this.deliveries = Objects.requireNonNull(deliveries);
    this.jdbc = Objects.requireNonNull(jdbc);
    this.entityManager = Objects.requireNonNull(entityManager);
    this.human = Objects.requireNonNull(human);
    this.actors = Objects.requireNonNull(actors);
    this.profiles = Objects.requireNonNull(profiles);
    this.json = Objects.requireNonNull(json);
    this.codec = Objects.requireNonNull(codec);
    this.journal = Objects.requireNonNull(journal);
    this.approvals = Objects.requireNonNull(approvals);
    this.outbox = Objects.requireNonNull(outbox);
    local = new TransactionTemplate(Objects.requireNonNull(transactions));
    local.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public AccountInviteService.InviteSendResult send(
      AccountInvite invite,
      InviteEmailTemplate template,
      BiFunction<AccountInvite, InviteEmailTemplate, ProtectedInvitationMailOutbox.Mail> prepare) {
    return send(invite, () -> template, prepare);
  }

  public AccountInviteService.InviteSendResult send(
      AccountInvite invite, Supplier<InviteEmailTemplate> selectTemplate,
      BiFunction<AccountInvite, InviteEmailTemplate, ProtectedInvitationMailOutbox.Mail> prepare) {
    requireOutsideTransaction();
    if (invite == null || invite.getId() == null || selectTemplate == null || prepare == null)
      throw unavailable();
    long id = invite.getId();
    var existing = jdbc.queryForList(
        "SELECT state FROM protected_invitation_mail_outbox WHERE invite_id=?", String.class, id);
    if (existing.size() > 1) throw unavailable();
    // Saved SMTP results and unknown in-flight outcomes precede mutable profile/template/expiry.
    if (!existing.isEmpty()) return delivered(id, outbox.dispatch(id));
    InviteEmailTemplate template = selectTemplate.get();
    if (template == null || template.getId() == null) throw unavailable();
    AccountInvite current = current(invite.getId());
    var rows = jdbc.queryForList(
        "SELECT state FROM protected_invitation_authorization WHERE invite_id=?",
        String.class, current.getId());
    boolean first = rows.isEmpty();
    if (rows.size() > 1 || !first && !List.of("APPROVAL_REQUESTED", "READY").contains(rows.getFirst()))
      throw unavailable();
    FrozenApproval frozen = first
        ? journal.propose(current.getId(), proposal(current, human.getUserId()))
        : journal.pending(current.getId());
    VerifiedApproval verified;
    if (first) {
      verified = approve(frozen);
    } else {
      try {
        verified = approvals.reconcile(frozen);
      } catch (KeycloakProtectedInvitations.RemoteFailure failure) {
        if (failure.status() != 404) throw failure;
        verified = approve(frozen);
      }
    }
    journal.recordReady(frozen.approvalId(), verified);
    outbox.enqueue(id, () -> {
      AccountInvite owned = invites.findById(id).orElseThrow(ProtectedInvitationIssuance::unavailable);
      // The journal already owns this invite's JDBC row lock in the same JPA transaction.
      entityManager.refresh(owned);
      InviteEmailTemplate selected = templates.findById(template.getId())
          .orElseThrow(ProtectedInvitationIssuance::unavailable);
      entityManager.refresh(selected, LockModeType.PESSIMISTIC_READ);
      var mail = Objects.requireNonNull(prepare.apply(owned, selected));
      owned.setTokenHash(AccountInviteService.hash(mail.rawToken()));
      owned.setStatus(AccountInviteStatus.EMAIL_SENT);
      owned.setUpdateDate(LocalDateTime.now());
      invites.saveAndFlush(owned);
      return mail;
    });
    return delivered(id, outbox.dispatch(id));
  }

  private AccountInviteService.InviteSendResult delivered(long inviteId, InviteMailSendReceipt receipt) {
    long templateId = outbox.sentTemplateId(inviteId);
    String body = "Protected invitation content is stored encrypted ("
        + journal.pending(inviteId).approvalId() + ")";
    return local.execute(tx -> {
      AccountInvite invite = InviteRowHold.lock(invites, inviteId)
          .orElseThrow(ProtectedInvitationIssuance::unavailable);
      entityManager.refresh(invite);
      InviteEmailDelivery audit = deliveries.findByAccountInviteIdOrderByCreateDateDesc(inviteId)
          .stream().filter(row -> row.getStatus() == InviteEmailDeliveryStatus.SENT
              && Objects.equals(row.getTemplateId(), templateId)
              && REDACTED_SUBJECT.equals(row.getSubjectSnapshot())
              && body.equals(row.getBodySnapshot()))
          .findFirst().orElseGet(() -> deliveries.saveAndFlush(InviteEmailDelivery.builder()
              .accountInviteId(inviteId).templateId(templateId)
              .templateKind(InviteEmailTemplateKind.TENANT_INVITE)
              .recipientSnapshot(receipt.recipientEmail()).subjectSnapshot(REDACTED_SUBJECT)
              .bodySnapshot(body).status(InviteEmailDeliveryStatus.SENT)
              .sentAt(LocalDateTime.ofInstant(receipt.sentAt(), ZoneId.systemDefault()))
              .createDate(LocalDateTime.now()).build()));
      return new AccountInviteService.InviteSendResult(invite, audit, null, null);
    });
  }

  /** Pure reducing metadata; the cancellation owner persists its first selected IDs atomically. */
  public FrozenApproval restrictionProposal(AccountInvite invite) {
    if (invite == null) throw unavailable();
    return proposal(invite, invite.getCreatedByUserId());
  }

  private VerifiedApproval approve(FrozenApproval frozen) {
    if (!frozen.issuerSubject().equals(human.getUserId())) throw unavailable();
    return approvals.approve(frozen, human.getAccessToken());
  }

  private AccountInvite current(long id) {
    return local.execute(tx -> {
      AccountInvite invite = invites.findById(id).orElseThrow(ProtectedInvitationIssuance::unavailable);
      // Freeze the stored timestamp precision and current tuple, including under an OSIV context.
      entityManager.refresh(invite);
      return invite;
    });
  }

  private FrozenApproval proposal(AccountInvite invite, String issuerSubject) {
    if (invite.getId() == null || invite.getPurpose() != AccountInvitePurpose.INVITE
        || invite.getTenantId() == null || invite.getExpiresAt() == null
        || invite.getRecipientEmail() == null || !actors.isEnabled()) throw unavailable();
    var creator = actors.require(TaskPurpose.ACCOUNT_PROVISIONING);
    var setup = actors.require(TaskPurpose.INVITE_RESERVATIONS);
    if (creator.actor() != TaskActor.ACCOUNT_CREATE || setup.actor() != TaskActor.SETUP)
      throw unavailable();
    String profile = profiles.profileIds().get(invite.getTargetRole());
    if (profile == null) throw unavailable();
    UUID workflow = UUID.randomUUID();
    var scope = new LinkedHashMap<String, Object>();
    scope.put("workflowId", workflow.toString());
    scope.put("inviteId", invite.getId().toString());
    scope.put("authorizationRevision", 1L);
    scope.put("purpose", "INVITE");
    scope.put("recipient", invite.getRecipientEmail().trim().toLowerCase(Locale.ROOT));
    scope.put("protectedKind", invite.getTargetRole().name());
    scope.put("targetTenantIds", List.of(invite.getTenantId()));
    String reservation = invite.getTenantIdReservationToken();
    scope.put("tenantMode", reservation == null ? "EXISTING" : "RESERVED_NEW");
    scope.put("reservationBinding", reservation == null ? null : Map.of(
        "tenantId", invite.getTenantId(), "invitationWorkflowId", workflow.toString(),
        "capabilityDigest", json.digest("protected-reservation-capability/v1", Map.of(
            "workflowId", workflow.toString(), "tenantId", invite.getTenantId(),
            "capability", reservation))));
    scope.put("agencyBinding", invite.getAgencyId() == null ? null : Map.of(
        "agencyId", invite.getAgencyId(), "tenantId", invite.getTenantId()));
    scope.put("protectedProfileId", profile);
    scope.put("validUntil", Timestamp.valueOf(invite.getExpiresAt()).toInstant().toString());
    scope.put("creatorClient", creator.clientId());
    scope.put("creatorSubject", creator.serviceSubject());
    scope.put("setupClient", setup.clientId());
    scope.put("setupSubject", setup.serviceSubject());
    return codec.freeze(UUID.randomUUID(), json.canonical(Map.of("scope", scope)),
        profiles.realmId(), issuerSubject);
  }

  private static void requireOutsideTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Protected invitation issuance requires completed local SQL");
  }

  private static AccessDeniedException unavailable() {
    return new AccessDeniedException("Protected invitation approval is pending or unavailable");
  }
}
