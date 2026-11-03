package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.service.chat.DatabaseMemberSetLock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * #1418: the room for a selection of colleagues is members-only. On the real MariaDB schema built
 * by Liquibase this proves the new {@code chat.members_only} column (existing rows stay open), the
 * native chat queries that now select it, and the two participant lookups the endpoint relies on to
 * reuse a room for exactly the same people.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ChatMembersOnlyMariaDbIT {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    r.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    r.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
    r.add("spring.liquibase.enabled", () -> "true");
    r.add("spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    r.add("spring.liquibase.contexts", () -> "dev,seed");
    r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    r.add("multitenancy.enabled", () -> "false");
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired javax.sql.DataSource dataSource;
  @Autowired ChatRepository chats;
  @Autowired GroupChatParticipantRepository participants;

  /** The chat id has no database default; a range of its own keeps the rows apart from seeds. */
  private static final java.util.concurrent.atomic.AtomicLong nextChatId =
      new java.util.concurrent.atomic.AtomicLong(918_000_000L);

  private String owner;
  private String colleague;
  private String third;
  private String advice;
  private final List<Long> chatIds = new java.util.ArrayList<>();

  @BeforeEach
  void givenThreeColleaguesAndAnAdviceSeeker() {
    owner = consultant("owner");
    colleague = consultant("colleague");
    third = consultant("third");
    advice = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO user (user_id, username, email, language_formal, language_code,"
            + " encourage_2fa, create_date, update_date, magic_link_login_enabled,"
            + " temporary_account, tenant_id)"
            + " VALUES (?, ?, ?, 0, 'de', 0, NOW(), NOW(), 0, 0, 1)",
        advice,
        advice,
        advice + "@example.invalid");
  }

  @AfterEach
  void cleanup() {
    jdbc.update("DELETE FROM user_chat WHERE user_id = ?", advice);
    for (Long id : chatIds) {
      jdbc.update("DELETE FROM group_chat_participant WHERE series_id = ?", id);
      jdbc.update("DELETE FROM chat WHERE id = ?", id);
    }
    jdbc.update("DELETE FROM user WHERE user_id = ?", advice);
    for (String id : List.of(owner, colleague, third)) {
      jdbc.update("DELETE FROM consultant WHERE consultant_id = ?", id);
    }
  }

  @Test
  void aChatCreatedWithoutTheColumnIsNotMembersOnly() {
    var id = chat(null);

    assertThat(chats.findById(id).orElseThrow().isMembersOnly()).isFalse();
  }

  @Test
  void theFlagIsStoredAndReadBackThroughTheNativeQueries() {
    var open = chat(false);
    var closed = chat(true);
    jdbc.update(
        "INSERT INTO user_chat (id, user_id, chat_id) VALUES (?, ?, ?)", open, advice, open);
    jdbc.update(
        "INSERT INTO user_chat (id, user_id, chat_id) VALUES (?, ?, ?)", closed, advice, closed);

    assertThat(chats.findById(closed).orElseThrow().isMembersOnly()).isTrue();
    assertThat(chats.findById(open).orElseThrow().isMembersOnly()).isFalse();
    var assigned = chats.findAssignedByUserId(advice);
    assertThat(assigned).extracting(c -> c.getId()).containsExactlyInAnyOrder(open, closed);
    assertThat(assigned.stream().filter(c -> c.getId().equals(closed)).findFirst().orElseThrow())
        .extracting(c -> c.isMembersOnly())
        .isEqualTo(true);
    assertThat(assigned.stream().filter(c -> c.getId().equals(open)).findFirst().orElseThrow())
        .extracting(c -> c.isMembersOnly())
        .isEqualTo(false);
  }

  @Test
  void aRoomIsFoundOnlyForExactlyTheSameMembers() {
    var pair = chat(true);
    member(pair, owner, "OWNER");
    member(pair, colleague, "CO_MODERATOR");
    var trio = chat(true);
    member(trio, owner, "OWNER");
    member(trio, colleague, "CO_MODERATOR");
    member(trio, third, "CO_MODERATOR");
    var otherPair = chat(true);
    member(otherPair, colleague, "OWNER");
    member(otherPair, third, "CO_MODERATOR");

    assertThat(participants.findSeriesIdsWithExactParticipants(owner, List.of(owner, colleague), 2))
        .containsExactly(pair);
    assertThat(
            participants.findSeriesIdsWithExactParticipants(
                owner, List.of(owner, colleague, third), 3))
        .containsExactly(trio);
    assertThat(participants.findSeriesIdsWithExactParticipants(owner, List.of(owner, third), 2))
        .isEmpty();
    // The creator narrows the search: a room the creator is not in is never offered.
    assertThat(
            participants.findSeriesIdsWithExactParticipants(
                colleague, List.of(colleague, third), 2))
        .containsExactly(otherPair);
  }

  @Test
  void membershipOfASessionsGroupChatIsLookedUpByItsSessionId() {
    var chat = chat(true);
    member(chat, owner, "OWNER");

    assertThat(participants.existsByChatIdAndConsultantId(chat + 1000, owner)).isTrue();
    assertThat(participants.existsByChatIdAndConsultantId(chat + 1000, colleague)).isFalse();
    assertThat(participants.existsByChatIdAndConsultantId(chat + 2000, owner)).isFalse();
  }

  @Test
  void theMemberSetLockExcludesAnotherReplicaOnTheDatabase() {
    // Two instances stand for two replicas: they share no JVM lock, only the database.
    var replicaA = new DatabaseMemberSetLock(dataSource, 1);
    var replicaB = new DatabaseMemberSetLock(dataSource, 1);
    var people = List.of(owner, colleague);

    try (var held = replicaA.acquire(people)) {
      assertThat(held).isNotNull();
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> replicaB.acquire(List.of(colleague, owner)))
          .isInstanceOfSatisfying(
              CustomValidationHttpStatusException.class,
              busy ->
                  assertThat(busy.getCustomHttpHeaders().getFirst("X-Reason"))
                      .isEqualTo("INTERNAL_CHAT_BUSY"));
      // Other people are not held up.
      try (var other = replicaB.acquire(List.of(owner, third))) {
        assertThat(other).isNotNull();
      }
    }
    // Released: the next request gets it.
    try (var again = replicaB.acquire(people)) {
      assertThat(again).isNotNull();
    }
  }

  private String consultant(String name) {
    var id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO consultant (consultant_id, username, first_name, last_name, email, tenant_id)"
            + " VALUES (?, ?, ?, 'Synthetic', ?, 1)",
        id,
        id,
        name,
        id + "@example.invalid");
    return id;
  }

  /**
   * @param membersOnly {@code null} leaves the column out, as a row from before #1418 does
   */
  private long chat(Boolean membersOnly) {
    var id = nextChatId.incrementAndGet();
    var columns =
        "id, topic, consulting_type, initial_start_date, start_date, duration, is_repetitive,"
            + " is_active, consultant_id_owner, conversation_type";
    var values = "?, 'Synthetic', 0, NOW(), NOW(), 60, 0, 1, ?, 'INTERNAL_GROUP'";
    var args = new java.util.ArrayList<Object>(List.of(id, owner));
    if (membersOnly != null) {
      columns += ", members_only";
      values += ", ?";
      args.add(membersOnly);
    }
    jdbc.update("INSERT INTO chat (" + columns + ") VALUES (" + values + ")", args.toArray());
    chatIds.add(id);
    return id;
  }

  private void member(long chat, String consultantId, String role) {
    jdbc.update(
        "INSERT INTO group_chat_participant (chat_id, series_id, consultant_id, participant_role)"
            + " VALUES (?, ?, ?, ?)",
        chat + 1000,
        chat,
        consultantId,
        role);
  }
}
