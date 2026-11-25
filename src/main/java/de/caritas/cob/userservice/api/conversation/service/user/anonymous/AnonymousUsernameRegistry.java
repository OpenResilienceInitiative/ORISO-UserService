package de.caritas.cob.userservice.api.conversation.service.user.anonymous;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.port.out.IdentityUsernameAvailability;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.GeneratedPseudonyms;
import de.caritas.cob.userservice.api.service.identity.GuestIdentityCatalog;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Hands out the User-ID of an anonymous guest. Every guest gets an animal handle from the one
 * global identity catalog ({@code katze_sam_4821}) — the one the guest rolled on the entry page, or
 * one the server rolls for clients that do not send a choice. Counter names such as {@code anon_7}
 * are never minted again (ORISO-Frontend#2346); existing ones stay valid.
 */
@Component
@RequiredArgsConstructor
public class AnonymousUsernameRegistry {

  /** Rolls before giving up; a collision is rare, an exhausted namespace means an outage. */
  static final int MAX_ATTEMPTS = 32;

  private static final String SERVER_ROLL_LOCALE = "de";

  private final @NonNull UserService userService;
  private final @NonNull ConsultantService consultantService;
  private final @NonNull IdentityUsernameAvailability identityUsernameAvailability;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull GuestIdentityCatalog guestIdentityCatalog;
  private final @NonNull GeneratedPseudonyms generatedPseudonyms;
  private final UsernameTranscoder usernameTranscoder = new UsernameTranscoder();

  /**
   * Rolls a free animal handle for a guest whose client did not send one.
   *
   * @return the encoded username
   * @throws ServiceUnavailableException when no free handle turned up within {@link #MAX_ATTEMPTS}
   */
  public synchronized String generateUniqueUsername() {
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      var candidate = guestIdentityCatalog.next(SERVER_ROLL_LOCALE).username();
      if (!isUsernameOccupied(candidate)) {
        return usernameTranscoder.encodeUsername(candidate);
      }
    }
    throw new ServiceUnavailableException("Guest names are temporarily unavailable");
  }

  /**
   * Keeps the handle the guest rolled. Anonymity rests on the dice, so only a handle the generator
   * can produce is accepted — never a typed name.
   *
   * @param handle the plain handle the guest chose, e.g. {@code katze_sam_4821}
   * @return the encoded username
   * @throws BadRequestException when the generator cannot produce the handle
   * @throws ConflictException when the handle is already taken; the guest rolls again
   */
  public synchronized String claimChosenUsername(String handle) {
    if (!generatedPseudonyms.containsHandle(handle)) {
      throw new BadRequestException("The guest name was not rolled by the name generator");
    }
    if (isUsernameOccupied(handle)) {
      throw new ConflictException("The guest name is already taken");
    }
    return usernameTranscoder.encodeUsername(handle);
  }

  /**
   * Nothing to release: names are checked against the database, Keycloak and Matrix rather than
   * held in memory. Kept so the deletion workflow can call it for every asker, whatever the name.
   *
   * @param encodedUsername the encoded username of a deleted asker
   */
  public void removeRegistryIdByUsername(String encodedUsername) {
    // Intentionally empty.
  }

  private boolean isUsernameOccupied(String username) {
    // The guest namespace is GLOBAL, not per tenant: Matrix user IDs are global and the anonymous
    // live-chat queue is deliberately cross-tenant. The DB lookups below are tenant-filtered, so
    // without the technical-context bypass a caller in tenant 83 would not see a guest of tenant
    // 1, hand the name out again, and Matrix would reject it with M_USER_IN_USE. Keycloak is
    // already global.
    return TenantContext.supplyAcrossTenants(
            () ->
                userService.findUserByUsername(username).isPresent()
                    || consultantService.getConsultantByUsername(username).isPresent())
        || !identityUsernameAvailability.isUsernameAvailable(username)
        || existsInMatrix(username);
  }

  /**
   * A localpart can survive in Matrix while absent from MariaDB and Keycloak (a rolled-back or
   * externally cleaned-up account). It has been written in two forms over time — the encoded
   * username and, for older accounts, the plain one — so both are checked.
   */
  private boolean existsInMatrix(String username) {
    return matrixSynapseService.userExists(usernameTranscoder.encodeUsername(username))
        || matrixSynapseService.userExists(username);
  }
}
