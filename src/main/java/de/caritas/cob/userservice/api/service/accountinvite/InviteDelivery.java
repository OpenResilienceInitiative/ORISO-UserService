package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.InviteSendResult;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.function.Consumer;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Renders an invite mail with a fresh link, sends it and writes the delivery audit. */
@Slf4j
@Component
@RequiredArgsConstructor
public class InviteDelivery {

  private static final int TOKEN_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final @NonNull InviteAcceptUrlBuilder inviteAcceptUrlBuilder;
  private final @NonNull InviteMailDispatchService inviteMailDispatchService;
  private final @NonNull InviteEmailDeliveryFailureRecorder deliveryFailureRecorder;
  private final @NonNull InviteEmailDeliveryRepository deliveryRepository;
  private final @NonNull PlatformTransactionManager transactionManager;

  /** A rendered mail and the raw token behind its link; the invite is not changed. */
  public record Prepared(
      AccountInvite invite,
      InviteEmailTemplate template,
      String rawToken,
      String acceptUrl,
      String subject,
      String body,
      LocalDateTime createdAt) {

    public String tokenHash() {
      return AccountInviteService.hash(rawToken);
    }

    Prepared withInvite(AccountInvite stored) {
      return new Prepared(stored, template, rawToken, acceptUrl, subject, body, createdAt);
    }
  }

  /** The link target follows the role: tenant admins onboard in the Admin, others in the App. */
  public Prepared prepare(AccountInvite invite, InviteEmailTemplate template, LocalDateTime now) {
    String rawToken = generateToken();
    String acceptUrl = inviteAcceptUrlBuilder.buildAcceptUrl(invite.getTargetRole(), rawToken);
    return new Prepared(
        invite,
        template,
        rawToken,
        acceptUrl,
        AccountInviteService.render(template.getSubject(), invite, acceptUrl),
        AccountInviteService.renderBody(template.getBody(), invite, acceptUrl),
        now);
  }

  /**
   * Sends a mail whose link is already committed. A confirmed "not sent" runs {@code
   * onConfirmedNotSent}; an uncertain outcome keeps the claim so a retry cannot mail twice.
   *
   * @param auditInviteId the committed invite a FAILED audit row may reference
   * @param auditConfirmedNotSent false when the invite row is removed on a confirmed failure
   */
  public InviteSendResult deliver(
      Prepared prepared,
      Long auditInviteId,
      boolean auditConfirmedNotSent,
      Consumer<SmtpSendException> onConfirmedNotSent) {
    InviteMailSendReceipt receipt;
    try {
      receipt = send(prepared);
    } catch (SmtpSendException sendFailure) {
      recordFailure(
          sendFailure.isConfirmedNotSent() && !auditConfirmedNotSent ? null : auditInviteId,
          prepared,
          sendFailure);
      if (sendFailure.isConfirmedNotSent()) {
        onConfirmedNotSent.accept(sendFailure);
      } else {
        log.warn(
            "Invite {} has an uncertain SMTP delivery outcome; keeping the claim to prevent a"
                + " duplicate send",
            prepared.invite().getId(),
            sendFailure);
      }
      throw sendFailure;
    }
    InviteEmailDelivery delivery = sentDelivery(prepared, receipt);
    try {
      TransactionTemplate audit = new TransactionTemplate(transactionManager);
      audit.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      InviteEmailDelivery unsaved = delivery;
      delivery = audit.execute(transaction -> deliveryRepository.saveAndFlush(unsaved));
    } catch (RuntimeException auditFailure) {
      log.error(
          "Invite {} was sent but its delivery audit could not be recorded",
          prepared.invite().getId(),
          auditFailure);
    }
    return new InviteSendResult(
        prepared.invite(), delivery, prepared.rawToken(), prepared.acceptUrl());
  }

  /**
   * Sends inside the caller's transaction. {@code markSent} runs only after SMTP confirmed the
   * handover, then the SENT audit row is stored in the same transaction.
   */
  public InviteEmailDelivery sendNow(Prepared prepared, Long auditInviteId, Runnable markSent) {
    InviteMailSendReceipt receipt;
    try {
      receipt = send(prepared);
    } catch (SmtpSendException sendFailure) {
      recordFailure(auditInviteId, prepared, sendFailure);
      throw sendFailure;
    }
    markSent.run();
    return deliveryRepository.save(sentDelivery(prepared, receipt));
  }

  private InviteMailSendReceipt send(Prepared prepared) {
    // The dispatcher wraps the body in the branded layout and renders the link as a button.
    return inviteMailDispatchService.send(
        prepared.invite().getRecipientEmail(),
        prepared.subject(),
        prepared.body(),
        prepared.acceptUrl(),
        prepared.invite().getTenantId(),
        prepared.template().getLanguage());
  }

  private static InviteEmailDelivery sentDelivery(
      Prepared prepared, InviteMailSendReceipt receipt) {
    return InviteEmailDelivery.builder()
        .accountInviteId(prepared.invite().getId())
        .templateId(prepared.template().getId())
        .templateKind(prepared.template().getKind())
        .recipientSnapshot(prepared.invite().getRecipientEmail())
        .subjectSnapshot(prepared.subject())
        .bodySnapshot(prepared.body())
        .status(InviteEmailDeliveryStatus.SENT)
        .sentAt(LocalDateTime.ofInstant(receipt.sentAt(), ZoneId.systemDefault()))
        .createDate(prepared.createdAt())
        .build();
  }

  /** Best effort, in its own transaction; never masks the send failure. */
  private void recordFailure(Long auditInviteId, Prepared prepared, SmtpSendException sendFailure) {
    if (auditInviteId == null) {
      return;
    }
    try {
      deliveryFailureRecorder.recordFailure(
          auditInviteId,
          prepared.template(),
          prepared.invite().getRecipientEmail(),
          prepared.subject(),
          prepared.body(),
          sendFailure.getMessage());
    } catch (RuntimeException auditFailure) {
      log.warn(
          "Could not persist FAILED invite delivery audit row ({})",
          auditFailure.getClass().getSimpleName());
    }
  }

  private static String generateToken() {
    byte[] token = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(token);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
  }
}
