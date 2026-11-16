package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService.CallRoomOrigin;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallObservationService.Outcome;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

/**
 * US#1628 on the real schema: a group chat and its technical shadow session share one Matrix room,
 * exactly as {@code CreateChatFacade} writes them. A call in that room registers and reads back as
 * the group chat's call.
 */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  MatrixCallObservationService.class,
  MatrixCallConversationResolver.class,
  MatrixCallStateService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MatrixCallGroupShadowSessionMariaDbIT {
  private static final org.testcontainers.containers.MariaDBContainer<?> DATABASE =
      new org.testcontainers.containers.MariaDBContainer<>("mariadb:10.11")
          .withDatabaseName("userservice");

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
  }

  private static final long TENANT = 7L;

  @Autowired private MatrixCallObservationService observations;
  @Autowired private MatrixCallStateService callStates;
  @Autowired private MatrixCallConversationResolver resolver;
  @Autowired private ChatRepository chats;
  @Autowired private SessionRepository sessions;
  @Autowired private UserRepository users;
  @Autowired private ConsultantRepository consultants;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private MatrixSynapseService matrix;
  @MockitoBean private EventNotificationService notifications;
  @MockitoBean private MatrixCallLifecycleService lifecycle;
  @MockitoBean private MatrixCallGuestService callGuests;
  // #1642 added these dependencies of the observation and state services; this group call has no
  // session binding to bind or revive.
  @MockitoBean private MatrixSessionCallBindingService sessionBindings;
  @MockitoBean private SessionCallRevival revival;

  @Test
  void aCallInAGroupChatRoomWithItsShadowSessionRegistersAndReadsAsTheChatsCall() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String room = "!group-" + suffix + ":synthetic.oriso.test";
    String media = "!media-" + suffix + ":synthetic.oriso.test";
    String callId = "call-" + suffix;
    var seeded = seedGroupChatWithShadowSession(room);
    String owner = seeded.owner().getMatrixUserId();
    when(matrix.getCallRoomMembers(room)).thenReturn(Optional.of(List.of(owner)));
    when(matrix.getCallRoomBinding(media, owner))
        .thenReturn(Optional.of(Map.of("call_id", callId, "source_room_id", room)));
    when(matrix.getCallRoomOrigin(media, owner))
        .thenReturn(Optional.of(new CallRoomOrigin(owner, owner)));
    var actor =
        new AuthenticatedUser(
            seeded.owner().getId(),
            seeded.owner().getUsername(),
            Set.of(UserRole.CONSULTANT.getValue()),
            "synthetic-token",
            Set.of());
    actor.setTenantId(TENANT);

    // Both rows really sit in the one room: the shape every Dev group chat has.
    assertThat(sessions.findByMatrixRoomId(room)).isPresent();
    assertThat(chats.findByMatrixRoomId(room)).isPresent();
    var conversation = resolver.resolveForWriting(room).orElseThrow();
    assertThat(conversation.getChatId()).isEqualTo(seeded.chat().getId());
    assertThat(conversation.getSessionId()).isNull();

    assertThat(observations.register(actor, room, callId, media, false)).isEqualTo(Outcome.OK);

    var state = callStates.read(actor, room, callId).orElseThrow();
    assertThat(state.callId()).isEqualTo(callId);
    assertThat(state.state()).isEqualTo("running");
  }

  private record Seeded(Consultant owner, Chat chat, Session shadow) {}

  private Seeded seedGroupChatWithShadowSession(String room) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              var owner = consultant();
              String systemUserId = "group-chat-system-" + TENANT;
              var systemUser =
                  users
                      .findByUserIdAndDeleteDateIsNull(systemUserId)
                      .orElseGet(
                          () -> {
                            var user =
                                new User(
                                    systemUserId,
                                    null,
                                    systemUserId,
                                    systemUserId + "@oriso.local",
                                    true);
                            user.setTenantId(TENANT);
                            user.setNotificationsEnabled(false);
                            user.setCreateDate(LocalDateTime.now());
                            user.setUpdateDate(LocalDateTime.now());
                            user.setLanguageCode(LanguageCode.de);
                            return users.save(user);
                          });
              var start = LocalDateTime.now();
              var chat =
                  chats.save(
                      Chat.builder()
                          .topic("Synthetic internal group")
                          .createDate(start)
                          .updateDate(start)
                          .consultingTypeId(1)
                          .chatOwner(owner)
                          .initialStartDate(start)
                          .startDate(start)
                          .duration(60)
                          .repetitive(false)
                          .conversationType(ConversationType.INTERNAL_GROUP)
                          .matrixRoomId(room)
                          .active(true)
                          .build());
              // Field by field as CreateChatFacade writes it.
              var shadow = new Session();
              shadow.setUser(systemUser);
              shadow.setConsultingTypeId(1);
              shadow.setPostcode("00000");
              shadow.setAgencyId(1L);
              shadow.setStatus(Session.SessionStatus.IN_PROGRESS);
              shadow.setRegistrationType(Session.RegistrationType.REGISTERED);
              shadow.setTeamSession(true);
              shadow.setConsultant(owner);
              shadow.setTenantId(TENANT);
              shadow.setConversationType(ConversationType.INTERNAL_GROUP);
              shadow.setMatrixRoomId(room);
              shadow.setLanguageCode(LanguageCode.de);
              shadow.setIsConsultantDirectlySet(false);
              shadow.setCreateDate(LocalDateTime.now());
              shadow.setUpdateDate(LocalDateTime.now());
              return new Seeded(owner, chat, sessions.save(shadow));
            });
  }

  private Consultant consultant() {
    var id = UUID.randomUUID().toString();
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setTenantId(TENANT);
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
    return consultants.save(consultant);
  }
}
