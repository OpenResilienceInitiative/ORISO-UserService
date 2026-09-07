package de.caritas.cob.userservice.api.port.out;

import java.util.Optional;

/** Authoritative, unambiguous identity and tenant for shared APP password recovery. */
public interface IdentityPasswordResetTargetLookup {
  Optional<Target> findPasswordResetTarget(String input);

  record Target(String id, long tenantId, String email) {}
}
