package de.caritas.cob.userservice.api.service.identity.observation;

import static de.caritas.cob.userservice.api.service.identity.observation.IdentityObservation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.keycloak.*;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.helper.*;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

class IdentityObservationTest {
  final UsernameTranscoder transcoder = new UsernameTranscoder();
  final KeycloakIdentityOccupancyLookup provider = mock(KeycloakIdentityOccupancyLookup.class);
  final IdentityOccupancyRawReader reader = mock(IdentityOccupancyRawReader.class);
  AuthenticatedUser user;

  @BeforeEach
  void setup() {
    user =
        new AuthenticatedUser(
            "human",
            "operator",
            Set.of("user-admin", "agency-admin", "tenant-admin"),
            "unused",
            0L,
            Set.of(AuthorityValue.USER_ADMIN));
    when(provider.read(any())).thenReturn(new Layer(Source.KEYCLOAK, Status.COMPLETE, List.of()));
    for (var source : List.of(Source.ADMIN, Source.CONSULTANT, Source.ASKER))
      when(reader.read(eq(source), any()))
          .thenReturn(new Layer(source, Status.COMPLETE, List.of()));
  }

  IdentityProbe probe(String id, String name, String email) {
    return IdentityProbe.of(id, name, email, transcoder);
  }

  IdentityObservation observe(IdentityProbe probe) {
    return new IdentityObservationService(user, provider, reader).observe(probe);
  }

  @AfterEach
  void clear() {
    TenantContext.clear();
  }

  @Test
  void completeEmptyIsFreeOnlyAfterAllFourReads() {
    var result = observe(probe(null, "empty", null));
    assertThat(result.occupancy()).isEqualTo("FREE");
    assertThat(result.complete()).isTrue();
    assertThat(result.layers()).hasSize(4);
  }

  @Test
  void deniesTechnicalEvenWithPlatformRolesBeforeAnyRead() {
    user.setRoles(Set.of("technical", "user-admin", "agency-admin", "tenant-admin"));
    assertThatThrownBy(() -> observe(probe(null, "private", null)))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(provider, reader);
  }

  @Test
  void deniesTenantZeroWithoutPlatformAuthority() {
    user.setRoles(Set.of("user-admin"));
    assertThatThrownBy(() -> observe(probe(null, "private", null)))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(provider, reader);
  }

  @Test
  void deniesMissingUserAdminAuthority() {
    user.setGrantedAuthorities(Set.of());
    assertThatThrownBy(() -> observe(probe(null, "private", null)))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(provider, reader);
  }

  @Test
  void keepsEverySourceForSameIdentityEvenAcrossTenantsAndDeletedRows() {
    for (var source : Source.values()) {
      var fact =
          new Fact(
              source,
              "same",
              source == Source.KEYCLOAK ? null : "same",
              "x",
              "x@example.org",
              9L,
              source == Source.ADMIN ? "TENANT" : null,
              source == Source.ADMIN ? null : true,
              "2026-01-01",
              false);
      var layer = new Layer(source, Status.COMPLETE, List.of(fact));
      if (source == Source.KEYCLOAK) when(provider.read(any())).thenReturn(layer);
      else when(reader.read(eq(source), any())).thenReturn(layer);
    }
    var result = observe(probe("same", null, null));
    assertThat(result.status()).isEqualTo(200);
    assertThat(result.occupancy()).isEqualTo("OCCUPIED");
    assertThat(result.facts()).hasSize(4);
  }

  @Test
  void disjointSelectorOwnersConflict() {
    when(provider.read(any()))
        .thenReturn(
            new Layer(
                Source.KEYCLOAK,
                Status.COMPLETE,
                List.of(
                    new Fact(
                        Source.KEYCLOAK, "one", null, "alice", null, null, null, null, null, false),
                    new Fact(
                        Source.KEYCLOAK,
                        "two",
                        null,
                        null,
                        "mail@example.org",
                        null,
                        null,
                        null,
                        null,
                        true))));
    var result = observe(probe(null, "alice", "mail@example.org"));
    assertThat(result.status()).isEqualTo(409);
    assertThat(result.occupancy()).isEqualTo("CONFLICT");
  }

  @Test
  void identitySelectorDoesNotHideAnotherOwner() {
    when(provider.read(any()))
        .thenReturn(
            new Layer(
                Source.KEYCLOAK,
                Status.COMPLETE,
                List.of(
                    new Fact(
                        Source.KEYCLOAK,
                        "other",
                        null,
                        "alice",
                        null,
                        null,
                        null,
                        null,
                        null,
                        false))));
    var result = observe(probe("requested", "alice", null));
    assertThat(result.status()).isEqualTo(409);
    assertThat(result.occupancy()).isEqualTo("CONFLICT");
  }

  @Test
  void dependencyFailureRetainsPositiveCollisionAndNeverReturnsSuccess() {
    when(provider.read(any()))
        .thenReturn(
            new Layer(
                Source.KEYCLOAK,
                Status.COMPLETE,
                List.of(
                    new Fact(
                        Source.KEYCLOAK,
                        "one",
                        null,
                        "alice",
                        null,
                        null,
                        null,
                        null,
                        null,
                        false))));
    when(reader.read(eq(Source.ADMIN), any()))
        .thenThrow(new IllegalStateException("must not leak"));
    var result = observe(probe(null, "alice", null));
    assertThat(result.status()).isEqualTo(502);
    assertThat(result.complete()).isFalse();
    assertThat(result.occupancy()).isEqualTo("OCCUPIED");
    assertThat(result.facts()).hasSize(1);
  }

  @Test
  void incompleteEmptyNeverBecomesFree() {
    when(provider.read(any())).thenReturn(Layer.failed(Source.KEYCLOAK));
    var result = observe(probe(null, "alice", null));
    assertThat(result.status()).isEqualTo(502);
    assertThat(result.occupancy()).isEqualTo("UNKNOWN");
  }

  @Test
  void identityLimitIsConflictAndBoundsFacts() {
    var facts = new ArrayList<Fact>();
    for (int i = 0; i < 21; i++)
      facts.add(
          new Fact(
              Source.ASKER, "id" + i, "id" + i, "shared", null, null, null, false, null, null));
    when(reader.read(eq(Source.ASKER), any()))
        .thenReturn(new Layer(Source.ASKER, Status.LIMIT, facts));
    var result = observe(probe(null, "shared", null));
    assertThat(result.status()).isEqualTo(409);
    assertThat(result.complete()).isFalse();
    assertThat(result.facts()).extracting(Fact::identityId).doesNotHaveDuplicates().hasSize(20);
  }

  @Test
  void validatesSelectorsAndNormalizesEncodedUsername() {
    for (String invalid : List.of(" ", "a*", "a%", "a_", "a?", "a\nb", "a".repeat(256)))
      assertThatThrownBy(() -> probe(null, invalid, null))
          .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> probe(null, null, null)).isInstanceOf(IllegalArgumentException.class);
    var normalized = probe(null, transcoder.encodeUsername("Alice"), "MAIL@EXAMPLE.ORG");
    assertThat(normalized.usernames()).contains("Alice", transcoder.encodeUsername("Alice"));
    assertThat(normalized.matches("id", "alice", null)).isTrue();
    assertThat(normalized.matches("id", "prefixalice", null)).isFalse();
    assertThat(normalized.email()).isEqualTo("mail@example.org");
  }

  @Test
  void rawSqlIncludesDeletedRowsAcrossEveryTenantAndUsesExactUnion() {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:occupancy;MODE=MariaDB;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER");
    JdbcTemplate jdbc = new JdbcTemplate(ds);
    jdbc.execute("DROP ALL OBJECTS");
    for (var table : List.of("admin", "consultant", "user")) {
      jdbc.execute(
          "CREATE TABLE `"
              + table
              + "` ("
              + table
              + "_id VARCHAR(255), username VARCHAR(255), email VARCHAR(255), tenant_id BIGINT"
              + (table.equals("admin") ? ", type VARCHAR(6)" : ", delete_date TIMESTAMP")
              + ")");
      jdbc.update(
          "INSERT INTO `" + table + "` VALUES (?,?,?,?,?)",
          "id-" + table,
          "Alice",
          "mail@example.org",
          9,
          table.equals("admin") ? "TENANT" : java.sql.Timestamp.valueOf("2026-01-01 00:00:00"));
    }
    var real = new IdentityOccupancyRawReader(ds);
    TenantContext.setCurrentTenant(4L);
    var previous = TenantContext.getCurrentTenantData();
    for (var source : List.of(Source.ADMIN, Source.CONSULTANT, Source.ASKER)) {
      var result = real.read(source, probe("different", "alice", "else@example.org"));
      assertThat(result.status()).isEqualTo(Status.COMPLETE);
      assertThat(result.facts()).hasSize(1);
      assertThat(result.facts().getFirst().tenantId()).isEqualTo(9L);
      assertThat(result.facts().getFirst().deleted())
          .isEqualTo(source == Source.ADMIN ? null : true);
    }
    assertThat(TenantContext.getCurrentTenantData()).isSameAs(previous);
    jdbc.execute("DROP TABLE consultant");
    assertThatThrownBy(() -> real.read(Source.CONSULTANT, probe(null, "alice", null)))
        .isInstanceOf(RuntimeException.class);
    assertThat(TenantContext.getCurrentTenantData()).isSameAs(previous);
  }

  @Test
  void rawIdentityIdRemainsExactWithCaseInsensitiveDatabaseCollation() {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:identitycase;MODE=MariaDB;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER");
    JdbcTemplate jdbc = new JdbcTemplate(ds);
    jdbc.execute(
        "CREATE TABLE admin (admin_id VARCHAR_IGNORECASE(255), username VARCHAR(255), email VARCHAR(255), tenant_id BIGINT, type VARCHAR(6))");
    jdbc.update(
        "INSERT INTO admin VALUES (?,?,?,?,?)",
        "IDENTITY",
        "other",
        "other@example.org",
        7L,
        "TENANT");
    assertThat(
            new IdentityOccupancyRawReader(ds)
                .read(Source.ADMIN, probe("identity", null, null))
                .facts())
        .isEmpty();
  }

  @Test
  void rawBoundIncludesDeletedOrphanRowsAndCannotCertifyVacancy() {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:identitybound;MODE=MariaDB;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER");
    JdbcTemplate jdbc = new JdbcTemplate(ds);
    jdbc.execute(
        "CREATE TABLE `user` (user_id VARCHAR(255), username VARCHAR(255), email VARCHAR(255), tenant_id BIGINT, delete_date TIMESTAMP)");
    for (int n = 0; n < 22; n++)
      jdbc.update(
          "INSERT INTO `user` VALUES (?,?,?,?,?)",
          "id" + n,
          "shared",
          "shared@example.org",
          99L,
          java.sql.Timestamp.valueOf("2026-01-01 00:00:00"));
    var result = new IdentityOccupancyRawReader(ds).read(Source.ASKER, probe(null, "shared", null));
    assertThat(result.status()).isEqualTo(Status.LIMIT);
    assertThat(result.facts()).hasSize(20);
    assertThat(result.facts()).allMatch(f -> Boolean.TRUE.equals(f.deleted()));
  }

  @Test
  void decodedUsernameCaseAliasesAreExactMatches() {
    var encoded = transcoder.encodeUsername("Alice");
    assertThat(probe(null, "alice", null).matches("id", encoded, null)).isTrue();
    assertThat(
            probe(null, encoded.toLowerCase(java.util.Locale.ROOT), null)
                .matches("id", "ALICE", null))
        .isTrue();
  }

  @Test
  void rawDecoderAwareScanFindsAlternateEncodedCase() {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:identityencoded;MODE=MariaDB;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER");
    JdbcTemplate jdbc = new JdbcTemplate(ds);
    jdbc.execute(
        "CREATE TABLE admin (admin_id VARCHAR(255), username VARCHAR(255), email VARCHAR(255), tenant_id BIGINT, type VARCHAR(6))");
    jdbc.update(
        "INSERT INTO admin VALUES (?,?,?,?,?)",
        "existing",
        transcoder.encodeUsername("Alice"),
        "other@example.org",
        7L,
        "TENANT");
    var result = new IdentityOccupancyRawReader(ds).read(Source.ADMIN, probe(null, "alice", null));
    assertThat(result.status()).isEqualTo(Status.COMPLETE);
    assertThat(result.facts()).extracting(Fact::identityId).containsExactly("existing");
    jdbc.update(
        "INSERT INTO admin VALUES (?,?,?,?,?)",
        "malformed",
        "enc.bad!",
        "malformed@example.org",
        7L,
        "TENANT");
    assertThat(
            new IdentityOccupancyRawReader(ds)
                .read(Source.ADMIN, probe(null, "empty", null))
                .status())
        .isEqualTo(Status.LIMIT);
  }

  @Test
  void decoderCandidatePaginationDoesNotConfusePopulationWithExactMatches() {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:identityencodedpages;MODE=MariaDB;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER");
    JdbcTemplate jdbc = new JdbcTemplate(ds);
    jdbc.execute(
        "CREATE TABLE admin (admin_id VARCHAR(255), username VARCHAR(255), email VARCHAR(255), tenant_id BIGINT, type VARCHAR(6))");
    for (int n = 0; n < 101; n++)
      jdbc.update(
          "INSERT INTO admin VALUES (?,?,?,?,?)",
          "noise" + n,
          transcoder.encodeUsername("unrelated" + n),
          "noise@example.org",
          7L,
          "TENANT");
    jdbc.update(
        "INSERT INTO admin VALUES (?,?,?,?,?)",
        "zz-existing",
        transcoder.encodeUsername("Alice"),
        "other@example.org",
        7L,
        "TENANT");
    var result = new IdentityOccupancyRawReader(ds).read(Source.ADMIN, probe(null, "alice", null));
    assertThat(result.status()).isEqualTo(Status.COMPLETE);
    assertThat(result.facts()).extracting(Fact::identityId).containsExactly("zz-existing");
  }

  @Test
  void fullFinalRawCandidatePageIsIncompleteEvenWithNoExactMatches() {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:identityencodedlimit;MODE=MariaDB;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER");
    JdbcTemplate jdbc = new JdbcTemplate(ds);
    jdbc.execute(
        "CREATE TABLE admin (admin_id VARCHAR(255), username VARCHAR(255), email VARCHAR(255), tenant_id BIGINT, type VARCHAR(6))");
    var batch = new ArrayList<Object[]>();
    for (int n = 0; n < 2000; n++)
      batch.add(
          new Object[] {
            "noise" + n,
            transcoder.encodeUsername("unrelated" + n),
            "noise@example.org",
            7L,
            "TENANT"
          });
    jdbc.batchUpdate("INSERT INTO admin VALUES (?,?,?,?,?)", batch);
    var result = new IdentityOccupancyRawReader(ds).read(Source.ADMIN, probe(null, "empty", null));
    assertThat(result.status()).isEqualTo(Status.LIMIT);
    assertThat(result.facts()).isEmpty();
  }
}
