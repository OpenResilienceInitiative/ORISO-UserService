package de.caritas.cob.userservice.api.service.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;

/**
 * Turns a rendered mail into a {@code multipart/alternative} body.
 *
 * <p>Every ORISO mail now ships both parts. The design system generates them from one content
 * model, so the plain-text twin cannot drift from the HTML the way hand-kept pairs always do — and
 * the text part is what a text-only client, a screen reader in text mode, and a spam filter scoring
 * the message actually read. Sending HTML alone is a large part of why transactional mail lands in
 * junk folders.
 *
 * <p>Order matters: least-preferred part first, so a client that understands HTML picks the second.
 */
public final class OrisoEmailMime {

  private OrisoEmailMime() {}

  public static MimeMultipart alternative(OrisoEmailRenderer.RenderedEmail email)
      throws MessagingException {
    MimeBodyPart textPart = new MimeBodyPart();
    textPart.setText(email.text(), "UTF-8");

    MimeBodyPart htmlPart = new MimeBodyPart();
    htmlPart.setContent(email.html(), "text/html; charset=UTF-8");

    MimeMultipart alternative = new MimeMultipart("alternative");
    alternative.addBodyPart(textPart);
    alternative.addBodyPart(htmlPart);
    return alternative;
  }
}
