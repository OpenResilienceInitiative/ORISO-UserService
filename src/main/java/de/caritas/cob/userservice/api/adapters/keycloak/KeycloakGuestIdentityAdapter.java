package de.caritas.cob.userservice.api.adapters.keycloak;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.port.out.GuestIdentityAccount;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;

/** Initial ownership metadata and credentials are one create operation; no legacy rollback. */
@Component
@RequiredArgsConstructor
public class KeycloakGuestIdentityAdapter implements GuestIdentityAccount {
  private static final String MARKER_ATTRIBUTE = "oriso_guest_join_attempt";
  private final KeycloakClient client;
  private final de.caritas.cob.userservice.api.helper.UserHelper userHelper;

  @Override
  public String createOnly(
      String username, String password, Long tenantId, String ownershipMarker) {
    validate(username, tenantId, ownershipMarker);
    if (password == null || password.isBlank())
      throw new BadRequestException("Invalid guest password");
    var representation = new UserRepresentation();
    representation.setUsername(username);
    representation.setEnabled(true);
    representation.setAttributes(
        Map.of(
            "username",
            List.of(username),
            "userName",
            List.of(username),
            "tenantId",
            List.of(tenantId.toString()),
            MARKER_ATTRIBUTE,
            List.of(ownershipMarker)));
    var credential = new CredentialRepresentation();
    credential.setType(CredentialRepresentation.PASSWORD);
    credential.setTemporary(false);
    credential.setValue(password);
    representation.setCredentials(List.of(credential));
    try (var response = client.getUsersResource().create(representation)) {
      if (response.getStatus() == 409)
        throw new ConflictException("Guest identity username is occupied");
      if (response.getStatus() != 201) throw unavailable();
      String id = CreatedResponseUtil.getCreatedId(response);
      try {
        return readOwned(id, username, tenantId, ownershipMarker).getId();
      } catch (ConflictException exception) {
        // This create succeeded. Stripped metadata is not a confirmed collision and cannot
        // authorize a new candidate. Preserve its durable pending intent for reconciliation.
        throw unavailable();
      }
    } catch (ConflictException | ForbiddenException | ServiceUnavailableException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw unavailable();
    }
  }

  @Override
  public Optional<String> findOwned(String username, Long tenantId, String ownershipMarker) {
    validate(username, tenantId, ownershipMarker);
    try {
      var matches = client.getUsersResource().searchByUsername(username, true);
      if (matches == null || matches.size() > 1) throw unavailable();
      if (matches.isEmpty()) return Optional.empty();
      return Optional.of(
          readOwned(matches.getFirst().getId(), username, tenantId, ownershipMarker).getId());
    } catch (ConflictException | ForbiddenException | ServiceUnavailableException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw unavailable();
    }
  }

  @Override
  public void completeOwned(String id, String username, Long tenantId, String ownershipMarker) {
    validate(username, tenantId, ownershipMarker);
    try {
      var owned = readOwned(id, username, tenantId, ownershipMarker);
      String email = userHelper.getDummyEmail(id);
      if (!userHelper.isValidEmail(email)) throw unavailable();
      if (owned.getEmail() != null
          && !owned.getEmail().isBlank()
          && !email.equals(owned.getEmail())) {
        throw new ForbiddenException("Guest identity profile has changed");
      }
      var attributes = new LinkedHashMap<>(owned.getAttributes());
      attributes.put("userId", List.of(id));
      attributes.put("username", List.of(username));
      attributes.put("userName", List.of(username));
      // Keep mandatory root profile fields and the same generated email as the local user.
      // Never send enabled, required actions or credentials on replay.
      var patch = new UserRepresentation();
      patch.setUsername(username);
      patch.setEmail(email);
      patch.setEmailVerified(true);
      patch.setFirstName(owned.getFirstName());
      patch.setLastName(owned.getLastName());
      patch.setAttributes(attributes);
      var resource = client.getUsersResource().get(id);
      resource.update(patch);
      var role = client.getRealmResource().roles().get("user").toRepresentation();
      if (role == null || role.getId() == null || !"user".equals(role.getName()))
        throw unavailable();
      resource.roles().realmLevel().add(List.of(role));
      var assigned = resource.roles().realmLevel().listAll();
      if (assigned == null
          || assigned.stream()
              .noneMatch(
                  candidate ->
                      role.getId().equals(candidate.getId())
                          && "user".equals(candidate.getName()))) {
        throw unavailable();
      }
      var confirmed = readOwned(id, username, tenantId, ownershipMarker);
      if (!List.of(id).equals(confirmed.getAttributes().get("userId"))
          || !email.equals(confirmed.getEmail())
          || !Boolean.TRUE.equals(confirmed.isEmailVerified())) throw unavailable();
    } catch (ConflictException | ForbiddenException | ServiceUnavailableException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw unavailable();
    }
  }

  @Override
  public void deleteOwned(String id, String username, Long tenantId, String ownershipMarker) {
    validate(username, tenantId, ownershipMarker);
    try {
      try {
        readOwned(id, username, tenantId, ownershipMarker);
      } catch (jakarta.ws.rs.NotFoundException absent) {
        // A prior compensation may have succeeded despite its lost response.
        return;
      }
      var resource = client.getUsersResource().get(id);
      resource.remove();
      try {
        resource.toRepresentation();
      } catch (jakarta.ws.rs.NotFoundException absent) {
        return;
      }
      // A successful DELETE alone is not enough to authorize another candidate.
      throw unavailable();
    } catch (ConflictException | ForbiddenException | ServiceUnavailableException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw unavailable();
    }
  }

  private UserRepresentation readOwned(
      String id, String username, Long tenantId, String ownershipMarker) {
    if (id == null || id.isBlank()) throw unavailable();
    var user = client.getUsersResource().get(id).toRepresentation();
    if (user == null || !id.equals(user.getId()) || !username.equals(user.getUsername())) {
      throw unavailable();
    }
    var attributes = user.getAttributes();
    if (attributes == null
        || !isSingleAttribute(attributes.get(MARKER_ATTRIBUTE), "[a-zA-Z0-9_-]{1,128}")
        || !isSingleAttribute(attributes.get("tenantId"), "[1-9][0-9]{0,18}")) {
      // A provider may have accepted our create but stripped undeclared profile attributes.
      // This remains unknown across retries, never a license to orphan it and choose another name.
      throw unavailable();
    }
    if (!List.of(ownershipMarker).equals(attributes.get(MARKER_ATTRIBUTE))
        || !List.of(tenantId.toString()).equals(attributes.get("tenantId"))) {
      throw new ConflictException("Guest identity belongs to another request");
    }
    if (Boolean.FALSE.equals(user.isEnabled())) {
      throw new ForbiddenException("Guest identity is no longer active");
    }
    if (!Boolean.TRUE.equals(user.isEnabled())) throw unavailable();
    return user;
  }

  private boolean isSingleAttribute(List<String> values, String pattern) {
    return values != null
        && values.size() == 1
        && values.getFirst() != null
        && values.getFirst().matches(pattern);
  }

  private void validate(String username, Long tenantId, String marker) {
    if (username == null
        || !username.matches("[a-z0-9_]{3,30}")
        || tenantId == null
        || tenantId <= 0
        || marker == null
        || !marker.matches("[a-zA-Z0-9_-]{1,128}")) {
      throw new BadRequestException("Invalid guest identity binding");
    }
  }

  private ServiceUnavailableException unavailable() {
    // Never attach provider bodies/causes: they may include credentials or admin tokens.
    return new ServiceUnavailableException("Guest identity provisioning outcome is unknown");
  }
}
