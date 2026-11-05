package de.caritas.cob.userservice.api.service.livechat;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Lock an existing lifecycle row before user/claim rows; missing state never implies active. */
@Component
@RequiredArgsConstructor
public class LiveChatInactivityFence {
  private final @NonNull JdbcTemplate jdbc;
  private final @NonNull de.caritas.cob.userservice.api.workflow.accountinactivity
          .AccountInactivityEffects
      effects;

  /** Must follow inactivity, User and claim locks so application role mutations cannot race. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void requirePureAsker(String userId) {
    if (!java.util.Set.of(
            de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffects.Role
                .ASKER)
        .equals(effects.currentRoles(userId)))
      throw new ConflictException("Account is not eligible for conversion");
  }

  /** Cleanup is allowed for suspended or legacy accounts, while respecting an existing row. */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<String> lockIfPresent(String userId) {
    return jdbc.query(
        "SELECT status FROM account_inactivity WHERE identity_id=? FOR UPDATE",
        (rs, row) -> rs.getString(1),
        userId);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void requireActive(String userId) {
    List<String> statuses = lockIfPresent(userId);
    if (statuses.size() != 1 || !"ACTIVE".equals(statuses.getFirst())) {
      throw new ConflictException("Account lifecycle action prevents conversion");
    }
  }
}
