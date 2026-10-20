package de.caritas.cob.userservice.api.adapters.keycloak;

import static de.caritas.cob.userservice.api.service.identity.observation.IdentityObservation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.service.identity.observation.*;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.*;
import org.keycloak.representations.idm.UserRepresentation;

class KeycloakIdentityOccupancyLookupTest {
  final KeycloakClient client = mock(KeycloakClient.class);
  final UsersResource users = mock(UsersResource.class);

  IdentityProbe probe(String id, String name, String email) {
    return IdentityProbe.of(id, name, email, new UsernameTranscoder());
  }

  UserRepresentation user(String id, String name, boolean enabled) {
    var u = new UserRepresentation();
    u.setId(id);
    u.setUsername(name);
    u.setEnabled(enabled);
    return u;
  }

  KeycloakIdentityOccupancyLookup lookup() {
    when(client.getUsersResource()).thenReturn(users);
    return new KeycloakIdentityOccupancyLookup(client);
  }

  @Test
  void exactPagesIncludeDisabledAndRejectFuzzyResults() {
    var p = probe(null, "alice", null);
    var lookup = lookup();
    var first = new ArrayList<UserRepresentation>();
    for (int i = 0; i < 100; i++) first.add(user("noise" + i, "prefixalice", true));
    first.set(0, user("disabled", "ALICE", false));
    when(users.search(
            eq("alice"),
            isNull(),
            isNull(),
            isNull(),
            eq(0),
            eq(100),
            isNull(),
            isNull(),
            eq(true)))
        .thenReturn(first);
    when(users.search(
            eq("alice"),
            isNull(),
            isNull(),
            isNull(),
            eq(100),
            eq(100),
            isNull(),
            isNull(),
            eq(true)))
        .thenReturn(List.of(user("later", "alice", true)));
    when(users.search(
            eq(p.usernames().get(1)),
            isNull(),
            isNull(),
            isNull(),
            eq(0),
            eq(100),
            isNull(),
            isNull(),
            eq(true)))
        .thenReturn(List.of());
    var result = lookup.read(p);
    assertThat(result.status()).isEqualTo(Status.COMPLETE);
    assertThat(result.facts()).extracting(Fact::identityId).containsExactly("disabled", "later");
    assertThat(result.facts().getFirst().enabled()).isFalse();
  }

  @Test
  void fullLastPermittedPageIsIncompleteEvenWhenPostfilterIsEmpty() {
    var lookup = lookup();
    var rows = Collections.nCopies(100, user("noise", "prefixalice", true));
    when(users.search(
            anyString(),
            isNull(),
            isNull(),
            isNull(),
            anyInt(),
            eq(100),
            isNull(),
            isNull(),
            eq(true)))
        .thenReturn(rows);
    var result = lookup.read(probe(null, "alice", null));
    assertThat(result.status()).isEqualTo(Status.LIMIT);
    verify(users, never())
        .search(
            anyString(),
            isNull(),
            isNull(),
            isNull(),
            eq(2000),
            anyInt(),
            isNull(),
            isNull(),
            anyBoolean());
  }

  @Test
  void exactIdNotFoundIsAbsentButForbiddenIsFailure() {
    var lookup = lookup();
    var resource = mock(UserResource.class);
    when(users.get("id")).thenReturn(resource);
    when(resource.toRepresentation()).thenThrow(new NotFoundException());
    assertThat(lookup.read(probe("id", null, null)).status()).isEqualTo(Status.COMPLETE);
    doThrow(new ForbiddenException("secret")).when(resource).toRepresentation();
    assertThat(lookup.read(probe("id", null, null)).status()).isEqualTo(Status.DEPENDENCY_FAILURE);
  }

  @Test
  void failureAfterPositiveMatchRetainsIt() {
    var lookup = lookup();
    var resource = mock(UserResource.class);
    when(users.get("id")).thenReturn(resource);
    when(resource.toRepresentation()).thenReturn(user("id", "alice", false));
    when(users.search(
            anyString(),
            isNull(),
            isNull(),
            isNull(),
            anyInt(),
            anyInt(),
            isNull(),
            isNull(),
            eq(true)))
        .thenThrow(new ForbiddenException("secret"));
    var result = lookup.read(probe("id", "alice", null));
    assertThat(result.status()).isEqualTo(Status.DEPENDENCY_FAILURE);
    assertThat(result.facts()).hasSize(1);
  }

  @Test
  void decoderAwareCandidateScanFindsCaseAliasOnLaterPage() {
    var lookup = lookup();
    var noise =
        Collections.nCopies(
            100, user("noise", new UsernameTranscoder().encodeUsername("unrelated"), true));
    when(users.search(
            eq("enc."),
            isNull(),
            isNull(),
            isNull(),
            eq(0),
            eq(100),
            isNull(),
            isNull(),
            eq(false)))
        .thenReturn(noise);
    when(users.search(
            eq("enc."),
            isNull(),
            isNull(),
            isNull(),
            eq(100),
            eq(100),
            isNull(),
            isNull(),
            eq(false)))
        .thenReturn(
            List.of(
                user(
                    "existing",
                    new UsernameTranscoder().encodeUsername("Alice").toLowerCase(Locale.ROOT),
                    false)));
    var result = lookup.read(probe(null, "alice", null));
    assertThat(result.status()).isEqualTo(Status.COMPLETE);
    assertThat(result.facts()).extracting(Fact::identityId).containsExactly("existing");
  }

  @Test
  void truncatedOrMalformedCandidateScanCannotProveAbsence() {
    var lookup = lookup();
    var noise =
        Collections.nCopies(
            100, user("noise", new UsernameTranscoder().encodeUsername("unrelated"), true));
    when(users.search(
            eq("enc."),
            isNull(),
            isNull(),
            isNull(),
            anyInt(),
            eq(100),
            isNull(),
            isNull(),
            eq(false)))
        .thenReturn(noise);
    assertThat(lookup.read(probe(null, "empty", null)).status()).isEqualTo(Status.LIMIT);
    when(users.search(
            eq("enc."),
            isNull(),
            isNull(),
            isNull(),
            anyInt(),
            eq(100),
            isNull(),
            isNull(),
            eq(false)))
        .thenReturn(List.of(user("invalid", "enc.bad!", true)));
    assertThat(lookup.read(probe(null, "empty", null)).status()).isEqualTo(Status.LIMIT);
  }

  @Test
  void canonicalUnderscoreNamesRemainExactAndIncludeDisabledProviderMatches() {
    assertThatCode(
            () -> {
              String canonical = "marge_at_springfield.invalid";
              String encoded = new UsernameTranscoder().encodeUsername(canonical);
              var lookup = lookup();
              when(users.search(
                      eq(canonical),
                      isNull(),
                      isNull(),
                      isNull(),
                      eq(0),
                      eq(100),
                      isNull(),
                      isNull(),
                      eq(true)))
                  .thenReturn(
                      List.of(
                          user("owner_1439", canonical, false),
                          user("decoy", canonical.replace('_', 'X'), true)));
              when(users.search(
                      eq(encoded),
                      isNull(),
                      isNull(),
                      isNull(),
                      eq(0),
                      eq(100),
                      isNull(),
                      isNull(),
                      eq(true)))
                  .thenReturn(List.of(user("owner_1439", encoded, false)));
              for (String name : List.of(canonical, encoded)) {
                var result = lookup.read(probe(null, name, null));
                assertThat(result.status()).isEqualTo(Status.COMPLETE);
                assertThat(result.facts())
                    .extracting(Fact::identityId)
                    .containsExactly("owner_1439");
                assertThat(result.facts().getFirst().enabled()).isFalse();
              }
              verify(users, times(2))
                  .search(
                      eq(canonical),
                      isNull(),
                      isNull(),
                      isNull(),
                      eq(0),
                      eq(100),
                      isNull(),
                      isNull(),
                      eq(true));
              verify(users, never())
                  .search(
                      eq(canonical),
                      isNull(),
                      isNull(),
                      isNull(),
                      anyInt(),
                      anyInt(),
                      isNull(),
                      isNull(),
                      eq(false));
            })
        .doesNotThrowAnyException();
  }

  @Test
  void underscoreIdentityAndEmailSelectorsRemainExact() {
    assertThatCode(
            () -> {
              var lookup = lookup();
              var resource = mock(UserResource.class);
              var owner = user("owner_1439", "other", false);
              owner.setEmail("marge_test@example.invalid");
              var decoy = user("decoy", "other", true);
              decoy.setEmail("margeXtest@example.invalid");
              when(users.get("owner_1439")).thenReturn(resource);
              when(resource.toRepresentation()).thenReturn(owner);
              when(users.search(
                      isNull(),
                      isNull(),
                      isNull(),
                      eq("marge_test@example.invalid"),
                      eq(0),
                      eq(100),
                      isNull(),
                      isNull(),
                      eq(true)))
                  .thenReturn(List.of(owner, decoy));
              for (var selector :
                  List.of(
                      probe("owner_1439", null, null),
                      probe(null, null, "marge_test@example.invalid"))) {
                var result = lookup.read(selector);
                assertThat(result.status()).isEqualTo(Status.COMPLETE);
                assertThat(result.facts())
                    .extracting(Fact::identityId)
                    .containsExactly("owner_1439");
              }
            })
        .doesNotThrowAnyException();
  }
}
