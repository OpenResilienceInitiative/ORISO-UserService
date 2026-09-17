package de.caritas.cob.userservice.api.service.notification;

/** The same subject and HTML rendered by ConsultingTypeService for actual signing mail. */
public record DpaSigningEmailPreview(String subject, String html) {}
