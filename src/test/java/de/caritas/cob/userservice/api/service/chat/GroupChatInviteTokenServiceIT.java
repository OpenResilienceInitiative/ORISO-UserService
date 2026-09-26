package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("testing")
class GroupChatInviteTokenServiceIT {

  @Autowired private GroupChatInviteTokenService tokenService;
  @Autowired private ChatRepository chatRepository;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  private Long chatId;

  @AfterEach
  void cleanUp() {
    if (chatId != null) {
      chatRepository.deleteById(chatId);
    }
  }

  @Test
  void aStaleReaderReturnsTheCommittedInviteTokenInsteadOfReplacingIt() throws Exception {
    var owner = consultantRepository.findAll().iterator().next();
    var now = LocalDateTime.now();
    chatId =
        chatRepository
            .save(
                Chat.builder()
                    .topic("Legacy invite token")
                    .consultingTypeId(1)
                    .initialStartDate(now)
                    .startDate(now)
                    .duration(60)
                    .active(true)
                    .conversationType(ConversationType.SELF_HELP)
                    .chatOwner(owner)
                    .build())
            .getId();
    jdbcTemplate.update("UPDATE chat SET invite_token = NULL WHERE id = ?", chatId);

    var staleRead = new CountDownLatch(1);
    var tokenCommitted = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var staleReader =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .execute(
                          status -> {
                            assertThat(
                                    chatRepository.findById(chatId).orElseThrow().getInviteToken())
                                .isNull();
                            staleRead.countDown();
                            try {
                              assertThat(tokenCommitted.await(10, TimeUnit.SECONDS)).isTrue();
                            } catch (InterruptedException exception) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(exception);
                            }
                            return tokenService.tokenFor(chatId);
                          }));
      assertThat(staleRead.await(10, TimeUnit.SECONDS)).isTrue();
      var committed = tokenService.tokenFor(chatId);
      tokenCommitted.countDown();

      assertThat(committed).isNotBlank().isEqualTo(staleReader.get(10, TimeUnit.SECONDS));
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT invite_token FROM chat WHERE id = ?", String.class, chatId))
          .isEqualTo(committed);
    }
  }
}
