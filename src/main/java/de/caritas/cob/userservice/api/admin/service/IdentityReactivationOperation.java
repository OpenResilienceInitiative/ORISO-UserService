package de.caritas.cob.userservice.api.admin.service;

/** Durable generation-fenced claim for one exact soft-deleted identity reactivation. */
public record IdentityReactivationOperation(
    String userId, String operationId, String username, String email, Long tenantId) {}
