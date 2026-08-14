package de.caritas.cob.userservice.api.service.email.layout;

/**
 * A rendered mail: the branded HTML part and the plain-text alternative generated from the same
 * content. Both parts are always produced together so a multipart/alternative message can never
 * ship an empty or stale text part.
 */
public record BrandedEmail(String subject, String html, String plainText) {}
