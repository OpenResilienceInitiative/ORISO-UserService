package de.caritas.cob.userservice.api.service.temporaryaccount;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.TemporaryAccountSnapshot;
import de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountExpiryPolicy.ConversationFacts;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TemporaryAccountExpiryPolicyTest {

  private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 28, 10, 0);
  private final TemporaryAccountExpiryPolicy policy = new TemporaryAccountExpiryPolicy();

  private static TemporaryAccountSnapshot call() {
    return TemporaryAccountSnapshot.builder()
        .medium(de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType.VIDEO)
        .callDeleteAfterMinutes(15)
        .callMaxLifetimeHours(24)
        .chatInactivityDays(30)
        .messageRetention(MessageRetentionMode.GUEST_DECIDES)
        .build();
  }

  private static TemporaryAccountSnapshot chat(int days) {
    return TemporaryAccountSnapshot.builder()
        .medium(de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType.CHAT)
        .callDeleteAfterMinutes(15)
        .callMaxLifetimeHours(24)
        .chatInactivityDays(days)
        .messageRetention(MessageRetentionMode.GUEST_DECIDES)
        .build();
  }

  @Test
  void callAccount_Should_BeDue_AfterTheSnapshottedMinutesFromHangUp() {
    var hangUp = CREATED.plusMinutes(30);
    var facts = new ConversationFacts(false, null, hangUp, false);

    assertThat(policy.isDue(call(), CREATED, facts, hangUp.plusMinutes(14))).isFalse();
    assertThat(policy.isDue(call(), CREATED, facts, hangUp.plusMinutes(15))).isTrue();
  }

  @Test
  void callAccount_Should_StayWhileTheCallIsRunning_ButNeverPastTheHardCap() {
    var running = new ConversationFacts(false, null, CREATED.plusMinutes(5), true);

    assertThat(policy.isDue(call(), CREATED, running, CREATED.plusHours(3))).isFalse();
    assertThat(policy.isDue(call(), CREATED, running, CREATED.plusHours(24))).isTrue();
  }

  @Test
  void callAccount_WithoutAnyCall_Should_LiveUntilTheMaximum() {
    var none = new ConversationFacts(false, null, null, false);

    assertThat(policy.isDue(call(), CREATED, none, CREATED.plusHours(23))).isFalse();
    assertThat(policy.isDue(call(), CREATED, none, CREATED.plusHours(24))).isTrue();
  }

  @Test
  void callAccount_Should_UseItsOwnCopy_NotTheCurrentSettings() {
    var shortLived = call();
    shortLived.setCallMaxLifetimeHours(2);
    var none = new ConversationFacts(false, null, null, false);

    assertThat(policy.isDue(shortLived, CREATED, none, CREATED.plusHours(2))).isTrue();
    assertThat(policy.isDue(call(), CREATED, none, CREATED.plusHours(2))).isFalse();
  }

  @Test
  void chatAccount_Should_BeDue_When_TheCounsellorClosesOrArchives() {
    var closed = new ConversationFacts(true, CREATED.plusMinutes(1), null, false);

    assertThat(policy.isDue(chat(30), CREATED, closed, CREATED.plusMinutes(2))).isTrue();
  }

  @Test
  void chatAccount_Should_BeDue_AfterTheSnapshottedDaysWithoutActivity() {
    var lastActivity = CREATED.plusDays(3);
    var open = new ConversationFacts(false, lastActivity, null, false);

    assertThat(policy.isDue(chat(7), CREATED, open, lastActivity.plusDays(7).minusMinutes(1)))
        .isFalse();
    assertThat(policy.isDue(chat(7), CREATED, open, lastActivity.plusDays(7))).isTrue();
  }

  @Test
  void chatAccount_WithoutAConversation_Should_CountFromCreation() {
    var none = new ConversationFacts(false, null, null, false);

    assertThat(policy.isDue(chat(1), CREATED, none, CREATED.plusHours(23))).isFalse();
    assertThat(policy.isDue(chat(1), CREATED, none, CREATED.plusDays(1))).isTrue();
  }

  @Test
  void alreadyDeletedAccount_Should_NeverBeDueAgain() {
    var deleted = chat(1);
    deleted.setDeletedAt(CREATED.plusDays(2));
    var none = new ConversationFacts(true, null, null, false);

    assertThat(policy.isDue(deleted, CREATED, none, CREATED.plusDays(5))).isFalse();
  }
}
