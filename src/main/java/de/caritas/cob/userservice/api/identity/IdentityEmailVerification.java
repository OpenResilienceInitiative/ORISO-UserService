package de.caritas.cob.userservice.api.identity;

public record IdentityEmailVerification(
    boolean created, boolean createdBefore, boolean attemptsLeft, String email) {}
