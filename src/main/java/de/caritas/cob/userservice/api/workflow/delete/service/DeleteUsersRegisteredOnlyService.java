package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;

import de.caritas.cob.userservice.api.helper.CustomLocalDateTime;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Service to trigger deletion of askers with no running sessions. */
@Service
@RequiredArgsConstructor
public class DeleteUsersRegisteredOnlyService {

  private final @NonNull UserRepository userRepository;
  private final @NonNull DeleteUserAccountService deleteUserAccountService;
  private final @NonNull WorkflowErrorMailService workflowErrorMailService;

  private final @NonNull org.springframework.jdbc.core.JdbcTemplate jdbc;

  @Value("${user.registeredonly.deleteWorkflow.check.days}")
  private int userRegisteredOnlyDeleteWorkflowCheckDays;

  /** Deletes all askers with no running sessions before the set date. */
  public void deleteUserAccountsTimeSensitive() {
    var dateTimeToCheck =
        CustomLocalDateTime.nowInUtc()
            .with(LocalTime.MIDNIGHT)
            .minusDays(userRegisteredOnlyDeleteWorkflowCheckDays);
    deleteUserAccountsBefore(dateTimeToCheck);
  }

  /** Deletes all askers with no running sessions no matter when created. */
  public void deleteUserAccountsTimeInsensitive() {
    var startOfTomorrow = CustomLocalDateTime.nowInUtc().with(LocalTime.MIDNIGHT).plusDays(1);
    deleteUserAccountsBefore(startOfTomorrow);
  }

  private void deleteUserAccountsBefore(LocalDateTime dateTimeToCheck) {
    var workflowErrors =
        userRepository
            .findAllByDeleteDateNullAndNoRunningSessionsAndCreateDateOlderThan(dateTimeToCheck)
            .stream()
            // An enrolled identity belongs exclusively to the immutable inactivity lifecycle.
            // Do not bypass its activity, mixed-role or retry rules when old flags are re-enabled.
            .filter(
                user ->
                    jdbc.queryForList(
                            "SELECT identity_id FROM account_inactivity WHERE identity_id=?",
                            String.class,
                            user.getUserId())
                        .isEmpty())
            .map(deleteUserAccountService::performUserDeletion)
            .flatMap(Collection::stream)
            .collect(Collectors.toList());

    if (isNotEmpty(workflowErrors)) {
      workflowErrorMailService.buildAndSendErrorMail(workflowErrors);
    }
  }
}
