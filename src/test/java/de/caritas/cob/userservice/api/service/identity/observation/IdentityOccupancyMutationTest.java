package de.caritas.cob.userservice.api.service.identity.observation;

import static de.caritas.cob.userservice.api.service.identity.observation.IdentityObservation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.keycloak.*;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;

class IdentityOccupancyMutationTest {
  final UsernameTranscoder transcoder = new UsernameTranscoder();

  static Stream<org.junit.jupiter.params.provider.Arguments> rawSources() {
    return Stream.of(
        org.junit.jupiter.params.provider.Arguments.of(Source.ADMIN, false),
        org.junit.jupiter.params.provider.Arguments.of(Source.CONSULTANT, false),
        org.junit.jupiter.params.provider.Arguments.of(Source.ASKER, false),
        org.junit.jupiter.params.provider.Arguments.of(Source.ADMIN, true));
  }

  @ParameterizedTest
  @MethodSource("rawSources")
  void deletionBeforeNextRawPageCannotHidePersistentAlias(Source source, boolean numeric)
      throws Exception {
    String table =
        source == Source.ADMIN ? "admin" : source == Source.ASKER ? "user" : "consultant";
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MariaDB;NON_KEYWORDS=USER");
    try (var keepAlive = ds.getConnection()) {
      var jdbc = new JdbcTemplate(ds);
      jdbc.execute(
          "CREATE TABLE `"
              + table
              + "` ("
              + table
              + "_id "
              + (numeric ? "BIGINT" : "VARCHAR(36)")
              + " PRIMARY KEY, username VARCHAR(255), email VARCHAR(255), tenant_id BIGINT, "
              + (source == Source.ADMIN ? "type VARCHAR(6)" : "delete_date TIMESTAMP")
              + ")");
      for (int n = 0; n <= 100; n++) {
        Object key = numeric ? Long.valueOf(n - 100) : String.format(Locale.ROOT, "%04d", n);
        jdbc.update(
            "INSERT INTO `" + table + "` VALUES (?,?,?,?,?)",
            key,
            transcoder.encodeUsername(n == 100 ? "Alice" : "A" + n),
            "other@example.invalid",
            7L,
            source == Source.ADMIN ? "TENANT" : java.sql.Timestamp.valueOf("2026-01-01 00:00:00"));
      }
      var statements = new ArrayList<String>();
      var bindings = new ArrayList<Object>();
      var pages = new AtomicInteger();
      var moving =
          new DelegatingDataSource(ds) {
            @Override
            public Connection getConnection() throws java.sql.SQLException {
              var connection = super.getConnection();
              return (Connection)
                  Proxy.newProxyInstance(
                      getClass().getClassLoader(),
                      new Class<?>[] {Connection.class},
                      (proxy, method, args) -> {
                        boolean candidate =
                            method.getName().equals("prepareStatement")
                                && args[0] instanceof String sql
                                && sql.contains("LIKE 'enc.%'");
                        if (candidate) {
                          statements.add((String) args[0]);
                          if (pages.incrementAndGet() == 2)
                            jdbc.update(
                                "DELETE FROM `" + table + "` WHERE " + table + "_id = ?",
                                numeric ? Long.valueOf(-100) : "0000");
                        }
                        Object value = invoke(connection, method, args);
                        if (!candidate) return value;
                        return Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {PreparedStatement.class},
                            (p, m, a) -> {
                              if (m.getName().startsWith("set")
                                  && a != null
                                  && a.length > 1
                                  && a[0].equals(1)) bindings.add(a[1]);
                              return invoke(value, m, a);
                            });
                      });
            }
          };
      TenantContext.setCurrentTenant(41L);
      var previous = TenantContext.getCurrentTenantData();
      try {
        var result =
            new IdentityOccupancyRawReader(moving)
                .read(source, IdentityProbe.of(null, "alice", null, transcoder));
        assertThat(result.status()).isEqualTo(Status.COMPLETE);
        assertThat(result.facts())
            .extracting(Fact::identityId)
            .containsExactly(numeric ? "0" : "0100");
        assertThat(result.facts().getFirst().deleted())
            .isEqualTo(source == Source.ADMIN ? null : true);
        assertThat(TenantContext.getCurrentTenantData()).isSameAs(previous);
        assertThat(statements)
            .hasSize(2)
            .allSatisfy(sql -> assertThat(sql).doesNotContain("OFFSET"));
        assertThat(statements.getFirst()).doesNotContain(" > ");
        assertThat(statements.getLast()).contains(table + "_id > ?");
        assertThat(bindings).containsExactly(numeric ? Long.valueOf(-1) : "0099");
      } finally {
        TenantContext.clear();
      }
    }
  }

  private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  @Test
  void providerDeletionBetweenOffsetPagesCannotHidePersistentAlias() {
    var client = mock(KeycloakClient.class);
    var users = mock(UsersResource.class);
    when(client.getUsersResource()).thenReturn(users);
    var candidates = new ArrayList<UserRepresentation>();
    for (int n = 0; n <= 100; n++) {
      var row = new UserRepresentation();
      row.setId("row" + n);
      row.setUsername(transcoder.encodeUsername(n == 100 ? "Alice" : "A" + n));
      row.setEnabled(false);
      candidates.add(row);
    }
    when(users.search(
            eq("enc."),
            isNull(),
            isNull(),
            isNull(),
            anyInt(),
            anyInt(),
            isNull(),
            isNull(),
            eq(false)))
        .thenAnswer(
            call -> {
              int first = call.getArgument(4), maximum = call.getArgument(5);
              var result =
                  new ArrayList<>(
                      candidates.subList(
                          Math.min(first, candidates.size()),
                          Math.min(first + maximum, candidates.size())));
              if (first == 0) candidates.removeFirst();
              return result;
            });
    var result =
        new KeycloakIdentityOccupancyLookup(client)
            .read(IdentityProbe.of(null, "alice", null, transcoder));
    assertThat(result.status()).isEqualTo(Status.COMPLETE);
    assertThat(result.facts()).extracting(Fact::identityId).containsExactly("row100");
    assertThat(result.facts().getFirst().enabled()).isFalse();
    verify(users).search("enc.", null, null, null, 0, 2000, null, null, false);
    verify(users, times(1))
        .search(
            eq("enc."),
            isNull(),
            isNull(),
            isNull(),
            anyInt(),
            anyInt(),
            isNull(),
            isNull(),
            eq(false));
  }
}
