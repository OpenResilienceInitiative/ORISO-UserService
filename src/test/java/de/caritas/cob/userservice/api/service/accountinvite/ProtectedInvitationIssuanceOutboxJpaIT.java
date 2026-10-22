package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.KeycloakProtectedInvitations;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.FrozenApproval;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.VerifiedApproval;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.SendInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Real public/JPA orchestration with a synthetic, MAC-verified remote response. These cases do not
 * claim native human/session or SMTP-server proof.
 */
@TestPropertySource(properties = "TASK_IDENTITY_MODEL=FIVE_ACTORS")
class ProtectedInvitationIssuanceOutboxJpaIT extends ProtectedInvitationIssuanceJpaFixture {
  @org.springframework.test.context.bean.override.mockito.MockitoBean
  de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCancellation cancellation;
  @Autowired AccountLifecycleCodec json;
  @Autowired ProtectedInvitationCodec codec;

  @Test
  void approvedPublicSendCommitsTheLinkAndSendingBeforeSmtpAndKeepsTheAuditRedacted() {
    approveAndReconcile();
    var observed = new AtomicInteger();
    when(sender.send(any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              var saved = invites.findAll().getFirst();
              String link = call.getArgument(3);
              String token = link.substring(link.lastIndexOf('/') + 1);
              assertThat(saved.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
              assertThat(saved.getTokenHash()).isEqualTo(AccountInviteService.hash(token));
              assertThat(state(saved.getId())).isEqualTo("SENDING");
              assertThat(
                      jdbc.queryForObject(
                          "SELECT sealed_mail FROM protected_invitation_mail_outbox WHERE invite_id=?",
                          String.class,
                          saved.getId()))
                  .doesNotContain(token, link);
              observed.incrementAndGet();
              return new InviteMailSendReceipt(RECIPIENT, Instant.now());
            });

    var result = service.createAndSendInvite(command(), template.getId());

    assertThat(result).isNotNull();
    assertThat(observed).hasValue(1);
    assertThat(state(result.invite().getId())).isEqualTo("SENT");
    assertThat(result.delivery().getStatus()).isEqualTo(InviteEmailDeliveryStatus.SENT);
    assertThat(result.delivery().getTemplateId()).isEqualTo(template.getId());
    assertThat(result.delivery().getBodySnapshot()).doesNotContain("https://", "{{inviteLink}}");
    assertThat(result.rawToken()).isNull();
    assertThat(result.acceptUrl()).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT request_json FROM protected_invitation_authorization WHERE invite_id=?",
                String.class,
                result.invite().getId()))
        .doesNotContain(human.getAccessToken());
    verify(approvals).approve(any(), anyString());
  }

  @Test
  void lostApprovalReplyRecoversTheSameDurableProposalThroughSetupWithoutReapproval() {
    when(approvals.approve(any(), anyString()))
        .thenThrow(new KeycloakProtectedInvitations.RemoteFailure(0));
    when(approvals.reconcile(any())).thenAnswer(call -> verified(call.getArgument(0)));
    assertThatThrownBy(() -> service.createAndSendInvite(command(), template.getId()))
        .isInstanceOf(KeycloakProtectedInvitations.RemoteFailure.class);
    var saved = invites.findAll().getFirst();
    String original = proposal(saved.getId());

    var result = service.sendInvite(new SendInviteCommand(saved.getId(), template.getId()));

    assertThat(result).isNotNull();
    assertThat(result.invite().getId()).isEqualTo(saved.getId());
    assertThat(proposal(saved.getId())).isEqualTo(original);
    assertThat(state(saved.getId())).isEqualTo("SENT");
    verify(approvals).approve(any(), anyString());
    verify(sender).send(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void missingRemoteApprovalNeverSubstitutesAnotherCurrentHumanForTheOriginalIssuer() {
    when(approvals.approve(any(), anyString()))
        .thenThrow(new KeycloakProtectedInvitations.RemoteFailure(0));
    assertThatThrownBy(() -> service.createAndSendInvite(command(), template.getId()))
        .isInstanceOf(KeycloakProtectedInvitations.RemoteFailure.class);
    var saved = invites.findAll().getFirst();
    String original = proposal(saved.getId());
    when(approvals.reconcile(any())).thenThrow(new KeycloakProtectedInvitations.RemoteFailure(404));
    human.setUserId("f171689a-59f5-43c3-aa40-623ba9cf624f");

    assertThatThrownBy(
            () -> service.sendInvite(new SendInviteCommand(saved.getId(), template.getId())))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(proposal(saved.getId())).isEqualTo(original);
    assertThat(invites.findById(saved.getId()).orElseThrow().getTokenHash()).isNull();
    assertThat(state(saved.getId())).isEqualTo("ABSENT");
    verify(approvals).approve(any(), anyString());
    assertThat(deliveries.count()).isZero();
  }

  @Test
  void historicalSentRetryKeepsTheOriginalTemplateAndDoesNotMintOrSendAgain() {
    approveAndReconcile();
    var first = service.createAndSendInvite(command(), template.getId());
    assertThat(first).isNotNull();
    String originalTokenHash = first.invite().getTokenHash();
    var another =
        templates.saveAndFlush(
            InviteEmailTemplate.builder()
                .kind(InviteEmailTemplateKind.TENANT_INVITE)
                .name("Later template")
                .subject("Later subject")
                .body("Different {{inviteLink}} text")
                .language("en")
                .active(true)
                .createDate(LocalDateTime.now())
                .build());

    var retry = service.sendInvite(new SendInviteCommand(first.invite().getId(), another.getId()));

    assertThat(retry).isNotNull();
    assertThat(retry.delivery().getTemplateId()).isEqualTo(template.getId());
    assertThat(retry.delivery().getId()).isEqualTo(first.delivery().getId());
    assertThat(retry.invite().getTokenHash()).isEqualTo(originalTokenHash);
    assertThat(deliveries.count()).isEqualTo(1);
    verify(approvals).approve(any(), anyString());
    verify(sender).send(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void historicalSentRemainsReadableThroughThePublicServiceAfterLocalExpiry() {
    approveAndReconcile();
    var first = service.createAndSendInvite(command(), template.getId());
    assertThat(first).isNotNull();
    jdbc.update(
        "UPDATE account_invite SET status='EXPIRED',expires_at=?,token_hash=NULL,"
            + "active_recipient_key=NULL WHERE id=?",
        Timestamp.valueOf(LocalDateTime.now().minusDays(1)),
        first.invite().getId());

    var command = new SendInviteCommand(first.invite().getId(), template.getId());
    var retry = assertDoesNotThrow(() -> service.sendInvite(command));

    assertThat(retry).isNotNull();
    assertThat(retry.invite().getStatus()).isEqualTo(AccountInviteStatus.EXPIRED);
    assertThat(retry.invite().getTokenHash()).isNull();
    assertThat(retry.delivery().getId()).isEqualTo(first.delivery().getId());
    assertThat(state(first.invite().getId())).isEqualTo("SENT");
    verify(approvals).approve(any(), anyString());
    verify(sender).send(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void sealFailureRollsBackBothJpaTokenAndEncryptedOutboxBeforeTheSameProposalCanRetry() {
    approveAndReconcile();
    template.setBody("x".repeat(1_048_577));
    templates.saveAndFlush(template);
    assertThatThrownBy(() -> service.createAndSendInvite(command(), template.getId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Protected invitation mail unavailable");
    var saved = invites.findAll().getFirst();
    assertThat(saved.getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
    assertThat(saved.getTokenHash()).isNull();
    assertThat(state(saved.getId())).isEqualTo("ABSENT");
    String original = proposal(saved.getId());
    template.setBody("Please accept {{inviteLink}}");
    templates.saveAndFlush(template);

    var result = service.sendInvite(new SendInviteCommand(saved.getId(), template.getId()));

    assertThat(result).isNotNull();
    assertThat(result.invite().getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
    assertThat(state(saved.getId())).isEqualTo("SENT");
    assertThat(proposal(saved.getId())).isEqualTo(original);
    verify(approvals).approve(any(), anyString());
    verify(sender).send(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void acknowledgedResendTransfersExactlyOneHeldKeyAndCapabilityThenReplaysTheSameSentMail() {
    approveAndReconcile();
    when(sender.send(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new InviteMailSendReceipt(RECIPIENT, Instant.now()));
    var first = service.createAndSendInvite(command(), template.getId());
    var acknowledged = new java.util.concurrent.atomic.AtomicBoolean();
    org.mockito.Mockito.doAnswer(call -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      new org.springframework.transaction.support.TransactionTemplate(transactions)
          .executeWithoutResult(tx -> ((Runnable) call.getArgument(2)).run());
      acknowledged.set(true);
      return null;
    }).when(cancellation).cancel(org.mockito.ArgumentMatchers.eq(first.invite().getId()),
        org.mockito.ArgumentMatchers.eq(de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCancellation.Reason.SUPERSEDED), any());
    when(cancellation.withAcknowledged(org.mockito.ArgumentMatchers.eq(first.invite().getId()), any()))
        .thenAnswer(call -> {
          assertThat(acknowledged.get()).isTrue();
          return new org.springframework.transaction.support.TransactionTemplate(transactions)
              .execute(tx -> ((java.util.function.Supplier<?>) call.getArgument(1)).get());
        });
    var command = new AccountInviteService.SendInviteCommand(first.invite().getId(), template.getId());
    var replacement = service.resendInvite(command);
    var retry = service.resendInvite(command);
    assertThat(retry.invite().getId()).isEqualTo(replacement.invite().getId());
    assertThat(invites.count()).isEqualTo(2);
    var retired = invites.findById(first.invite().getId()).orElseThrow();
    var current = invites.findById(replacement.invite().getId()).orElseThrow();
    assertThat(retired.getStatus()).isEqualTo(AccountInviteStatus.SUPERSEDED);
    assertThat(retired.getSupersededByInviteId()).isEqualTo(current.getId());
    assertThat(retired.getActiveRecipientKey()).isNull();
    assertThat(retired.getTenantIdReservationToken()).isNull();
    assertThat(retired.getTokenHash()).isNull();
    assertThat(current.getActiveRecipientKey()).isEqualTo(RECIPIENT);
    assertThat(current.getTenantIdReservationToken()).isEqualTo(first.invite().getTenantIdReservationToken());
    assertThat(current.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
    assertThat(replacement.delivery().getStatus()).isEqualTo(InviteEmailDeliveryStatus.SENT);
    assertThat(replacement.rawToken()).isNull();
    assertThat(replacement.acceptUrl()).isNull();
    verify(sender, org.mockito.Mockito.times(2)).send(any(), any(), any(), any(), any(), any(), any());
    verify(approvals, org.mockito.Mockito.times(2)).approve(any(), anyString());
    verify(tenantIds, org.mockito.Mockito.never()).release(17L);
  }

  private void approveAndReconcile() {
    when(approvals.approve(any(), anyString()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return verified(call.getArgument(0));
            });
    when(approvals.reconcile(any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return verified(call.getArgument(0));
            });
  }

  private VerifiedApproval verified(FrozenApproval frozen) throws Exception {
    JsonNode scope = json.read(frozen.requestJson(), JsonNode.class).path("scope");
    var receipt = new LinkedHashMap<String, Object>();
    receipt.put("approvalId", frozen.approvalId().toString());
    receipt.put("scopeDigest", "c".repeat(64));
    receipt.put("issuerSubject", frozen.issuerSubject());
    receipt.put("bindingVersion", 1L);
    receipt.put("profileVersion", 1L);
    receipt.put("validUntil", scope.path("validUntil").asText());
    receipt.put("approvalRequestDigest", frozen.requestDigest());
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.canonical(receipt).getBytes(StandardCharsets.UTF_8));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
    receipt.put(
        "receipt",
        encoded
            + "."
            + HexFormat.of()
                .formatHex(
                    mac.doFinal(
                        ("protected-approval-receipt/v1\n" + encoded)
                            .getBytes(StandardCharsets.UTF_8))));
    var response = new LinkedHashMap<String, Object>();
    response.put("approvalId", frozen.approvalId().toString());
    response.put("scopeDigest", receipt.get("scopeDigest"));
    response.put("state", "AUTHORIZED");
    response.put("version", 0L);
    response.put("boundAttemptId", null);
    response.put("fencedExecution", null);
    response.put("approvalReceipt", receipt);
    response.put("transitionReceipt", null);
    response.put("approvalRequestDigest", frozen.requestDigest());
    return codec.verifyAuthorized(frozen, json.canonical(response));
  }

  private String proposal(long id) {
    return jdbc.queryForObject(
        "SELECT request_json FROM protected_invitation_authorization WHERE invite_id=?",
        String.class,
        id);
  }

  private String state(long id) {
    var values =
        jdbc.queryForList(
            "SELECT state FROM protected_invitation_mail_outbox WHERE invite_id=?",
            String.class,
            id);
    return values.isEmpty() ? "ABSENT" : values.getFirst();
  }
}
