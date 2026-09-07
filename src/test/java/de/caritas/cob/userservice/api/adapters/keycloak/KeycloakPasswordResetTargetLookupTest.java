package de.caritas.cob.userservice.api.adapters.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;

class KeycloakPasswordResetTargetLookupTest {
  private final KeycloakClient client = mock(KeycloakClient.class);
  private final UsersResource users = mock(UsersResource.class);
  private final UserResource resource = mock(UserResource.class);
  private final KeycloakPasswordResetTargetLookup lookup =
      new KeycloakPasswordResetTargetLookup(client);

  @BeforeEach
  void setup() {
    when(client.getUsersResource()).thenReturn(users);
  }

  private UserRepresentation identity(String id, String username) {
    var identity = new UserRepresentation();
    identity.setId(id);
    identity.setUsername(username);
    identity.setEmail("lisa@example.com");
    identity.setEnabled(true);
    identity.setEmailVerified(false);
    identity.setAttributes(Map.of("tenantId", List.of("40")));
    return identity;
  }

  private void username(String value, UserRepresentation... identities) {
    when(users.search(value, null, null, null, 0, 2, null, true, true))
        .thenReturn(List.of(identities));
  }

  private void full(UserRepresentation identity) {
    when(users.get(identity.getId())).thenReturn(resource);
    when(resource.toRepresentation()).thenReturn(identity);
  }

  @Test
  void exactUsernameResolvesFullTenantWithoutRequiringVerifiedEmail() {
    var identity = identity("lisa", "lisa@example.com");
    username("lisa@example.com", identity);
    full(identity);
    var target = lookup.findPasswordResetTarget("lisa@example.com").orElseThrow();
    assertThat(target.id()).isEqualTo("lisa");
    assertThat(target.tenantId()).isEqualTo(40);
    verify(users, never()).search(null, null, null, "lisa@example.com", 0, 2, null, true, true);
  }

  @Test
  void duplicateEmailNeverSelectsFirstOwner() {
    when(users.search(null, null, null, "lisa@example.com", 0, 2, null, true, true))
        .thenReturn(List.of(identity("one", "one"), identity("two", "two")));
    assertThat(lookup.findPasswordResetTarget("lisa@example.com")).isEmpty();
    verify(users, never()).get(anyString());
  }

  @Test
  void uniqueEmailUsesExactBoundedQuery() {
    var identity = identity("lisa", "unrelated-username");
    when(users.search(null, null, null, "lisa@example.com", 0, 2, null, true, true))
        .thenReturn(List.of(identity));
    full(identity);
    assertThat(lookup.findPasswordResetTarget("lisa@example.com")).isPresent();
  }

  @Test
  void differentAliasOwnersReject() {
    username("lisa", identity("one", "lisa"));
    String encoded = new UsernameTranscoder().encodeUsername("lisa");
    username(encoded, identity("two", encoded));
    assertThat(lookup.findPasswordResetTarget("lisa")).isEmpty();
    verify(users, never()).get(anyString());
  }

  @Test
  void aliasesWithSameIdentityDeduplicate() {
    var identity = identity("one", "lisa");
    username("lisa", identity);
    String encoded = new UsernameTranscoder().encodeUsername("lisa");
    username(encoded, identity("one", encoded));
    full(identity);
    assertThat(lookup.findPasswordResetTarget("lisa")).isPresent();
  }

  @Test
  void disabledUsernameMustNotFallThroughToEmail() {
    var identity = identity("one", "lisa@example.com");
    identity.setEnabled(false);
    username("lisa@example.com", identity);
    full(identity);
    assertThat(lookup.findPasswordResetTarget("lisa@example.com")).isEmpty();
    verify(users, never()).search(null, null, null, "lisa@example.com", 0, 2, null, true, true);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "x", "", "9223372036854775808"})
  void invalidTenantRejects(String tenant) {
    var identity = identity("one", "lisa");
    identity.setAttributes(Map.of("tenantId", List.of(tenant)));
    username("lisa", identity);
    full(identity);
    assertThat(lookup.findPasswordResetTarget("lisa")).isEmpty();
  }

  @Test
  void missingAndMultipleTenantValuesReject() {
    var identity = identity("one", "lisa");
    username("lisa", identity);
    full(identity);
    identity.setAttributes(null);
    assertThat(lookup.findPasswordResetTarget("lisa")).isEmpty();
    identity.setAttributes(Map.of("tenantId", List.of("1", "40")));
    assertThat(lookup.findPasswordResetTarget("lisa")).isEmpty();
  }

  @Test
  void fuzzyResultRejects() {
    username("lisa", identity("one", "lisa-extra"));
    assertThat(lookup.findPasswordResetTarget("lisa")).isEmpty();
    verify(users, never()).get(anyString());
  }

  @Test
  void changedFullIdentityCannotUseStaleSearchMatch() {
    var listed = identity("one", "lisa");
    username("lisa", listed);
    when(users.get("one")).thenReturn(resource);
    when(resource.toRepresentation()).thenReturn(identity("other-id", "lisa"));
    assertThat(lookup.findPasswordResetTarget("lisa")).isEmpty();
    when(resource.toRepresentation()).thenReturn(identity("one", "renamed"));
    assertThat(lookup.findPasswordResetTarget("lisa")).isEmpty();
  }
}
