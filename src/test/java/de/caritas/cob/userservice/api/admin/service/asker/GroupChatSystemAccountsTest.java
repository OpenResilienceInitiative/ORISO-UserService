package de.caritas.cob.userservice.api.admin.service.asker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GroupChatSystemAccountsTest {

  @Test
  void isTechnical_Should_recogniseTheTenantScopedAndLegacySystemUsers() {
    assertThat(GroupChatSystemAccounts.isTechnical("group-chat-system-12")).isTrue();
    assertThat(GroupChatSystemAccounts.isTechnical("group-chat-system-default")).isTrue();
    assertThat(GroupChatSystemAccounts.isTechnical("group-chat-system")).isTrue();
    assertThat(GroupChatSystemAccounts.isTechnical("0f2cca9c-9303-4791-a0a5-a1ce16f1524f"))
        .isFalse();
    assertThat(GroupChatSystemAccounts.isTechnical(null)).isFalse();
  }
}
