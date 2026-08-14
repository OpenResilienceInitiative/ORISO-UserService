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

  /**
   * Sends a genuine {@code multipart/alternative} message (ORISO-UserService#914): the plain-text
   * part is what plain-text-only clients and most spam filters read, the HTML part carries the
   * branded layout. Same strict contract as {@link #send(InviteSmtpSettings, String, String,
   * String)} — receipt or {@link SmtpSendException}, never silent success.
   *
   * <p>Defaulted so implementations predating #914 keep compiling; they simply drop the text part.
   */
  default InviteMailSendReceipt send(
      InviteSmtpSettings settings,
      String recipient,
      String subject,
      String htmlBody,
      String plainTextBody) {
    return send(settings, recipient, subject, htmlBody);
  }
}
