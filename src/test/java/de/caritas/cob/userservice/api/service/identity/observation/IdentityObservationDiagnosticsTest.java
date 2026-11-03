package de.caritas.cob.userservice.api.service.identity.observation;

import static de.caritas.cob.userservice.api.service.identity.observation.IdentityObservation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.caritas.cob.userservice.api.adapters.keycloak.*;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.helper.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.UsersResource;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;

class IdentityObservationDiagnosticsTest {
  final String marker = "PrivateDependencyFailureT614";
  final IdentityProbe probe = IdentityProbe.of(null, "alice", null, new UsernameTranscoder());

  @Test
  void serviceTranslationLogsOnlyFixedSourceAndExceptionType() {
    var provider = mock(KeycloakIdentityOccupancyLookup.class);
    var raw = mock(IdentityOccupancyRawReader.class);
    var user =
        new AuthenticatedUser(
            "human",
            "operator",
            Set.of("agency-admin", "tenant-admin", "user-admin"),
            "unused",
            0L,
            Set.of(AuthorityValue.USER_ADMIN));
    when(provider.read(any())).thenReturn(new Layer(Source.KEYCLOAK, Status.COMPLETE, List.of()));
    when(raw.read(eq(Source.ADMIN), any())).thenThrow(new IllegalArgumentException(marker));
    for (var source : List.of(Source.CONSULTANT, Source.ASKER))
      when(raw.read(eq(source), any())).thenReturn(new Layer(source, Status.COMPLETE, List.of()));
    try (var capture = new Capture(IdentityObservationService.class)) {
      assertThat(new IdentityObservationService(user, provider, raw).observe(probe).status())
          .isEqualTo(502);
      capture.assertFailure("ADMIN", "java.lang.IllegalArgumentException", marker);
    }
  }

  @Test
  void providerTranslationLogsOnlyFixedSourceAndExceptionType() {
    var client = mock(KeycloakClient.class);
    var users = mock(UsersResource.class);
    when(client.getUsersResource()).thenReturn(users);
    when(users.search(
            anyString(),
            isNull(),
            isNull(),
            isNull(),
            anyInt(),
            anyInt(),
            isNull(),
            isNull(),
            anyBoolean()))
        .thenThrow(new IllegalStateException(marker));
    try (var capture = new Capture(KeycloakIdentityOccupancyLookup.class)) {
      assertThat(new KeycloakIdentityOccupancyLookup(client).read(probe).status())
          .isEqualTo(Status.DEPENDENCY_FAILURE);
      capture.assertFailure("KEYCLOAK", "java.lang.IllegalStateException", marker);
    }
  }

  @Test
  void rawCandidateTranslationLogsOnlyFixedSourceAndExceptionTypeAndRetainsFacts()
      throws Exception {
    var ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:" + UUID.randomUUID());
    try (var keepAlive = ds.getConnection()) {
      var jdbc = new JdbcTemplate(ds);
      jdbc.execute(
          "CREATE TABLE admin (admin_id VARCHAR(36), username VARCHAR(255), email VARCHAR(255), tenant_id BIGINT, type VARCHAR(6))");
      jdbc.update(
          "INSERT INTO admin VALUES (?,?,?,?,?)",
          "owner",
          "alice",
          "other@example.invalid",
          7L,
          "TENANT");
      var failing =
          new DelegatingDataSource(ds) {
            @Override
            public Connection getConnection() throws java.sql.SQLException {
              var connection = super.getConnection();
              return (Connection)
                  Proxy.newProxyInstance(
                      getClass().getClassLoader(),
                      new Class<?>[] {Connection.class},
                      (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement")
                            && args[0] instanceof String sql
                            && sql.contains("LIKE 'enc.%'"))
                          throw new java.sql.SQLException(marker, "42000");
                        try {
                          return method.invoke(connection, args);
                        } catch (InvocationTargetException failure) {
                          throw failure.getCause();
                        }
                      });
            }
          };
      try (var capture = new Capture(IdentityOccupancyRawReader.class)) {
        var result = new IdentityOccupancyRawReader(failing).read(Source.ADMIN, probe);
        assertThat(result.status()).isEqualTo(Status.DEPENDENCY_FAILURE);
        assertThat(result.facts()).extracting(Fact::identityId).containsExactly("owner");
        capture.assertFailure("ADMIN", "org.springframework.jdbc.BadSqlGrammarException", marker);
      }
    }
  }

  private static final class Capture implements AutoCloseable {
    final Logger logger;
    final ListAppender<ILoggingEvent> events = new ListAppender<>();

    Capture(Class<?> owner) {
      logger = (Logger) LoggerFactory.getLogger(owner);
      events.start();
      logger.addAppender(events);
    }

    void assertFailure(String source, String type, String marker) {
      assertThat(events.list)
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.getFormattedMessage())
                    .isEqualTo("Identity occupancy source " + source + " failed: " + type)
                    .doesNotContain(marker);
                assertThat(event.getThrowableProxy()).isNull();
              });
    }

    @Override
    public void close() {
      logger.detachAppender(events);
      events.stop();
    }
  }
}
