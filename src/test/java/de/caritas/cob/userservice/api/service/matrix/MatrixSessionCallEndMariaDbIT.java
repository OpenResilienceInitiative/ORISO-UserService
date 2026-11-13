package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService.CallRoomOrigin;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;

/**
 * FE#2209 review on MariaDB: an explicit end and a session call's binding never interleave, with or
 * without an observation row, and a failing end notification cannot leave the binding live.
 */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  MatrixCallObservationService.class,
  MatrixSessionCallBindingService.class,
  MatrixCallBindingService.class,
  MatrixCallBindingWriter.class,
  MatrixCallConversationResolver.class,
  OrisoRoomRegistry.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MatrixSessionCallEndMariaDbIT {
  private static final MariaDBContainer<?> DATABASE =
      new MariaDBContainer<>("mariadb:10.11").withDatabaseName("userservice");

  static {
    DATABASE.start();
  }

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add(
        "spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MariaDBDialect");
    registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
    registry.add("spring.sql.init.mode", () -> "never");
    registry.add(
        "spring.datasource.hikari.connection-init-sql",
        () -> "SET SESSION innodb_lock_wait_timeout = 10");
  }

  @Autowired private MatrixCallObservationService observations;
  @Autowired private MatrixSessionCallBindingService sessionBindings;
  @Autowired private MatrixCallBindingRepository bindings;
  @Autowired private UserRepository users;
  @Autowired private ConsultantRepository consultants;
  @Autowired private SessionRepository sessions;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private MatrixSynapseService matrix;
  @MockitoBean private EventNotificationService notifications;
  @MockitoBean private MatrixCallLifecycleService lifecycle;

  private String source;
  private String media;
  private Consultant counsellor;
  private String askerMatrixId;
  private AuthenticatedUser actor;

  @BeforeEach
  void setUp() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    askerMatrixId = "@asker-" + suffix + ":example";
    source = "!end-race-source-" + suffix + ":example";
    media = "!end-race-media-" + suffix + ":example";
    var transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(
        status -> {
          var asker = users.save(user("asker-" + suffix, askerMatrixId));
          counsellor = consultants.save(consultant());
          var session =
              new Session(asker, 1, "12345", 1L, Session.SessionStatus.IN_PROGRESS, false);
          session.setTenantId(7L);
          session.setMatrixRoomId(source);
          session.setLanguageCode(LanguageCode.de);
          session.setIsConsultantDirectlySet(false);
          session.setConsultant(counsellor);
          session.setCreateDate(LocalDateTime.now());
          session.setUpdateDate(LocalDateTime.now());
          session.setSupervisionOptedOut(false);
          sessions.save(session);
        });
    actor = new AuthenticatedUser();
    actor.setUserId(counsellor.getId());
    actor.setTenantId(7L);
    actor.setRoles(Set.of("consultant"));
    String caller = counsellor.getMatrixUserId();
    when(lifecycle.isActiveRecipient(anyString())).thenReturn(true);
    when(matrix.getCallRoomMembers(source)).thenReturn(Optional.of(List.of(caller)));
    when(matrix.getCallRoomMembers(media)).thenReturn(Optional.of(List.of(caller)));
    when(matrix.getCallRoomBinding(media, caller))
        .thenReturn(Optional.of(Map.of("call_id", "call", "source_room_id", source)));
    when(matrix.getCallRoomOrigin(media, caller))
        .thenReturn(Optional.of(new CallRoomOrigin(caller, caller)));
    when(matrix.ensureAdminInRoom(anyString(), anyString())).thenReturn(true);
  }

  @Test
  void anEndWhileTheBindingIsBeingWrittenEndsItWithoutAnObservationRow() throws Exception {
    assertEndWaitsForTheBindingAndEndsIt();
  }

  @Test
  void anEndWhileTheBindingIsBeingWrittenEndsItWithAnObservationRow() throws Exception {
    assertThat(observations.register(actor, source, "call", media, true))
        .isEqualTo(MatrixCallObservationService.Outcome.OK);
    assertEndWaitsForTheBindingAndEndsIt();
  }

  /**
   * Bind is paused inside its write transaction (after the session lock, before the insert); the
   * end runs meanwhile. Ending wins: the binding that commits is ended, nothing stays live.
   */
  private void assertEndWaitsForTheBindingAndEndsIt() throws Exception {
    var bindInside = new CountDownLatch(1);
    var activeChecks = new AtomicInteger();
    when(lifecycle.isActiveRecipient(counsellor.getId()))
        .thenAnswer(
            invocation -> {
              // The second check is the recheck under the session lock.
              if (activeChecks.incrementAndGet() == 2) {
                bindInside.countDown();
                Thread.sleep(1500);
              }
              return true;
            });
    var pool = Executors.newFixedThreadPool(2);
    try {
      var bound = pool.submit(() -> sessionBindings.bind(actor, source, "call", media, true));
      assertThat(bindInside.await(10, TimeUnit.SECONDS)).isTrue();
      var ended = pool.submit(() -> observations.end(actor, source, "call"));
      assertThat(bound.get(20, TimeUnit.SECONDS)).isTrue();
      assertThat(ended.get(20, TimeUnit.SECONDS))
          .isEqualTo(MatrixCallObservationService.Outcome.OK);
    } finally {
      pool.shutdownNow();
    }
    assertNoLiveBinding();
  }

  @Test
  void anEndThatCommitsBeforeTheBindingsTransactionLeavesAMarkerTheBindingRefuses()
      throws Exception {
    String caller = counsellor.getMatrixUserId();
    // Bind has done its Matrix checks and found no row; the end lands before its transaction.
    when(matrix.getCallRoomOrigin(media, caller))
        .thenAnswer(
            invocation -> {
              var pool = Executors.newSingleThreadExecutor();
              try {
                assertThat(pool.submit(() -> observations.end(actor, source, "call")).get())
                    .isEqualTo(MatrixCallObservationService.Outcome.OK);
              } finally {
                pool.shutdownNow();
              }
              return Optional.of(new CallRoomOrigin(caller, caller));
            });

    assertThat(sessionBindings.bind(actor, source, "call", media, true)).isFalse();
    assertNoLiveBinding();
  }

  @Test
  void aFailingEndNotificationStillLeavesTheBindingEnded() {
    when(matrix.getCallRoomMembers(source))
        .thenReturn(Optional.of(List.of(counsellor.getMatrixUserId(), askerMatrixId)));
    assertThat(observations.register(actor, source, "call", media, true))
        .isEqualTo(MatrixCallObservationService.Outcome.OK);
    assertThat(sessionBindings.bind(actor, source, "call", media, true)).isTrue();
    doThrow(new IllegalStateException("notification store down"))
        .when(notifications)
        .createCallEndedNotification(
            any(), anyString(), anyBoolean(), anyString(), anyString(), anyLong(), anyLong());

    assertThatThrownBy(() -> observations.end(actor, source, "call"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(bindings.findBySourceRoomIdAndCallId(source, "call").orElseThrow().getEndedAt())
        .isNotNull();
  }

  private void assertNoLiveBinding() {
    Optional<MatrixCallBinding> binding = bindings.findBySourceRoomIdAndCallId(source, "call");
    binding.ifPresent(value -> assertThat(value.getEndedAt()).isNotNull());
  }

  private static User user(String id, String matrixId) {
    return User.builder()
        .userId(id)
        .username(id)
        .email(id + "@synthetic.oriso.test")
        .matrixUserId(matrixId)
        .tenantId(7L)
        .encourage2fa(true)
        .magicLinkLoginEnabled(false)
        .languageCode(LanguageCode.de)
        .createDate(LocalDateTime.now())
        .updateDate(LocalDateTime.now())
        .build();
  }

  private static Consultant consultant() {
    var id = UUID.randomUUID().toString();
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setTenantId(7L);
    consultant.setCreateDate(LocalDateTime.now());
    consultant.setUpdateDate(LocalDateTime.now());
    consultant.setUsername("synthetic-" + id.substring(0, 8));
    consultant.setFirstName("Synthetic");
    consultant.setLastName("C" + id.substring(0, 8));
    consultant.setEmail(id.substring(0, 8) + "@synthetic.oriso.test");
    consultant.setAppointments(null);
    consultant.setConsultantAgencies(new HashSet<>());
    consultant.setConsultantMobileTokens(new HashSet<>());
    consultant.setEncourage2fa(true);
    consultant.setNotifyEnquiriesRepeating(true);
    consultant.setNotifyNewChatMessageFromAdviceSeeker(true);
    consultant.setWalkThroughEnabled(true);
    consultant.setTeamConsultant(false);
    consultant.setMagicLinkLoginEnabled(false);
    consultant.setLanguageCode(LanguageCode.de);
    consultant.setMatrixUserId("@synthetic-" + id.substring(0, 8) + ":synthetic.oriso.test");
    return consultant;
  }
}
