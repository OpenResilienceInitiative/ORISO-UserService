package de.caritas.cob.userservice.api.service.accountinvite.mail;

import de.caritas.cob.userservice.api.exception.SmtpSendException;

/**
 * Strict SMTP send contract for account-invite mails: implementations either return an {@link
 * InviteMailSendReceipt} after the SMTP server accepted the message, or throw {@link
 * SmtpSendException}. Silent success is not allowed (TEN-INV-U6, mirrors the ConsultingTypeService
 * DpaMailTransport contract from U5).
 */
public interface InviteMailTransport {

  /**
   * Sends the given message via the provided SMTP settings.
   *
   * @return a receipt confirming the SMTP server accepted the message
   * @throws SmtpSendException if the message could not be handed over to the SMTP server
   */
  InviteMailSendReceipt send(
      InviteSmtpSettings settings, String recipient, String subject, String htmlBody);
}
