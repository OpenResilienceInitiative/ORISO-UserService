package de.caritas.cob.userservice.api.service.identity;

/** A single selectable identity; the avatar key is a bundled animal asset, never a URL. */
public record GuestIdentitySuggestion(String username, String displayName, String avatarKey) {}
