package de.caritas.cob.userservice.api.service.livechat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class LiveChatInactivityFenceTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffects
      effects =
          mock(
              de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffects
                  .class);
  private final LiveChatInactivityFence fence = new LiveChatInactivityFence(jdbc, effects);

  @Test
  void staleAskerTokenCannotOverrideCurrentMixedIdentityRoles() {
    when(effects.currentRoles("guest"))
        .thenReturn(
            java.util.Set.of(
                de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffects
                    .Role.ASKER,
                de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffects
                    .Role.CONSULTANT));
    assertThatThrownBy(() -> fence.requirePureAsker("guest")).isInstanceOf(ConflictException.class);
  }

  @Test
  void onlyExistingActiveLifecycleIsAccepted() {
    when(jdbc.query(anyString(), any(RowMapper.class), eq("guest")))
        .thenReturn(List.of("ACTIVE"), List.of(), List.of("SUSPENDING"), List.of("DELETING"));
    fence.requireActive("guest");
    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> fence.requireActive("guest")).isInstanceOf(ConflictException.class);
    }
  }
}
