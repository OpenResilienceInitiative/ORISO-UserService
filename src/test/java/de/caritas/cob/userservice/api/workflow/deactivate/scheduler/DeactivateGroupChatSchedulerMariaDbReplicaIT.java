package de.caritas.cob.userservice.api.workflow.deactivate.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Chat.ChatModality;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.deactivate.service.DeactivateGroupChatService;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
class DeactivateGroupChatSchedulerMariaDbReplicaIT {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 26, 5, 0);
  private static final String CONSULTANT_ID = "group-chat-replica-proof";
  private static final String ROOM_ID = "!group-chat-replica:matrix.test";

  @DynamicPropertySource
  private static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    registry.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
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
    registry.add("group.chat.deactivateworkflow.periodMinutes", () -> "0");
    registry.add("group.chat.deactivateworkflow.cron", () -> "0 0 0 1 1 ?");
  }

  @Autowired private DeactivateGroupChatService deactivationService;
  @Autowired private ChatRepository chatRepository;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;
  @MockitoBean private MatrixSynapseService matrixSynapseService;

  @BeforeEach
  void setUp() {
    deleteProofChat();
    consultantRepository.deleteById(CONSULTANT_ID);
    consultantRepository.save(createConsultant());
  }

  @AfterEach
  void cleanUp() {
    deleteProofChat();
    consultantRepository.deleteById(CONSULTANT_ID);
  }

  @Test
  void twoSchedulerInstancesShutdownOneExpiredMatrixChatExactlyOnce() throws Exception {
    var persistedChat = chatRepository.save(createExpiredChat());
    var bothSchedulersEntered = new CountDownLatch(2);
    var firstMatrixShutdownEntered = new CountDownLatch(1);
    var releaseFirstMatrixShutdown = new CountDownLatch(1);
    var tenantContextProvider = mock(TenantContextProvider.class);
    doAnswer(
            ignored -> {
              bothSchedulersEntered.countDown();
              await(bothSchedulersEntered);
              return null;
            })
        .when(tenantContextProvider)
        .setTechnicalContextIfMultiTenancyIsEnabled();
    when(matrixSynapseService.purgeRoom(ROOM_ID))
        .thenAnswer(
            ignored -> {
              firstMatrixShutdownEntered.countDown();
              await(releaseFirstMatrixShutdown);
              return true;
            });
    var first = new DeactivateGroupChatScheduler(deactivationService, tenantContextProvider);
    var second = new DeactivateGroupChatScheduler(deactivationService, tenantContextProvider);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var firstResult = executor.submit(() -> run(first, ready, start));
      var secondResult = executor.submit(() -> run(second, ready, start));

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(bothSchedulersEntered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(firstMatrixShutdownEntered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(firstResult.isDone()).isFalse();
      assertThat(secondResult.isDone()).isFalse();
      releaseFirstMatrixShutdown.countDown();
      firstResult.get(10, TimeUnit.SECONDS);
      secondResult.get(10, TimeUnit.SECONDS);
    } finally {
      start.countDown();
      releaseFirstMatrixShutdown.countDown();
      executor.shutdownNow();
    }

    assertThat(chatRepository.findById(persistedChat.getId())).isEmpty();
    verify(matrixSynapseService, times(1)).purgeRoom(ROOM_ID);
    verify(tenantContextProvider, times(2)).setTechnicalContextIfMultiTenancyIsEnabled();
  }

  private Chat createExpiredChat() {
    var owner = consultantRepository.findById(CONSULTANT_ID).orElseThrow();
    var chat = new Chat();
    chat.setTopic("Replica-safe Matrix group chat");
    chat.setConsultingTypeId(1);
    chat.setInitialStartDate(NOW.minusHours(2));
    chat.setStartDate(NOW.minusHours(2));
    chat.setDuration(30);
    chat.setRepetitive(false);
    chat.setRepeatCount(1);
    chat.setCurrentOccurrenceIndex(0);
    chat.setTimezone("UTC");
    chat.setChatModality(ChatModality.TEXT);
    chat.setConversationType(ConversationType.SELF_HELP);
    chat.setActive(true);
    chat.setMatrixRoomId(ROOM_ID);
    chat.setChatOwner(owner);
    chat.setCreateDate(NOW.minusHours(2));
    chat.setUpdateDate(NOW.minusHours(2));
    return chat;
  }

  private Consultant createConsultant() {
    var consultant = new Consultant();
    consultant.setId(CONSULTANT_ID);
    consultant.setMatrixUserId("@group-chat-owner:matrix.test");
    consultant.setUsername("group-chat-replica-proof");
    consultant.setFirstName("Group Chat");
    consultant.setLastName("Replica");
    consultant.setEmail("group-chat-replica@example.invalid");
    consultant.setEncourage2fa(true);
    consultant.setMagicLinkLoginEnabled(false);
    consultant.setNotifyEnquiriesRepeating(true);
    consultant.setNotifyNewChatMessageFromAdviceSeeker(true);
    consultant.setWalkThroughEnabled(true);
    consultant.setLanguageCode(LanguageCode.de);
    consultant.setStatus(ConsultantStatus.IN_PROGRESS);
    consultant.setCreateDate(NOW);
    consultant.setUpdateDate(NOW);
    return consultant;
  }

  private void run(
      DeactivateGroupChatScheduler scheduler, CountDownLatch ready, CountDownLatch start) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              ready.countDown();
              await(start);
              scheduler.performDeactivationWorkflow();
            });
  }

  private void deleteProofChat() {
    jdbcTemplate.update("DELETE FROM chat WHERE matrix_room_id = ?", ROOM_ID);
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent group-chat proof");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}
