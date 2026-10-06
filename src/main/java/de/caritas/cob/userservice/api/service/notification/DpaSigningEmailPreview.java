package de.caritas.cob.userservice.api.service.notification;

/** The subject and HTML part of the DPA signing mail, exactly as {@code send} transmits them. */
public record DpaSigningEmailPreview(String subject, String html) {}
