package de.caritas.cob.userservice.api.service.accountinvite.mail;

import java.time.Instant;

/**
 * Confirmation that the SMTP server accepted an invite mail. Returned only after the transport
 * completed without error, so callers may safely persist the delivery as SENT (TEN-INV-U6, mirrors
 * ConsultingTypeService's DpaMailSendReceipt).
 */
public record InviteMailSendReceipt(String recipientEmail, Instant sentAt) {}
