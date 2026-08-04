package de.caritas.cob.userservice.api.service.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProvisioningCompensatorTest {

  private SimpleMeterRegistry meterRegistry;
  private ProvisioningCompensator compensator;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    compensator = new ProvisioningCompensator(meterRegistry);
  }

  @Test
  void compensateIfIncompleteExecutesRegisteredStepsInReverseOrderAndMeasuresSuccess() {
    List<String> calls = new ArrayList<>();
    ProvisioningAttempt attempt =
        compensator.begin(ProvisioningWorkflow.LEGACY_ASKER_WITHOUT_SESSION);
    attempt.register(ProvisioningResource.IDENTITY_USER, () -> calls.add("identity-user"));
    attempt.register(ProvisioningResource.DATABASE_USER, () -> calls.add("database-user"));
    attempt.register(ProvisioningResource.LEGACY_CHAT_USER, () -> calls.add("legacy-chat-user"));

    CompensationResult result = attempt.compensateIfIncomplete();

    assertThat(calls).containsExactly("legacy-chat-user", "database-user", "identity-user");
    assertThat(result.successful()).isTrue();
    assertThat(result.attemptedSteps()).isEqualTo(3);
    assertThat(result.failedResources()).isEmpty();
    assertThat(
            meterRegistry
                .get(ProvisioningCompensator.ATTEMPT_METRIC)
                .tag("workflow", "legacy_asker_without_session")
                .tag("outcome", "success")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void completedAttemptDoesNotCompensateOrMeasureAnAttempt() {
    List<String> calls = new ArrayList<>();
    ProvisioningAttempt attempt = compensator.begin(ProvisioningWorkflow.REGISTERED_USER);
    attempt.register(ProvisioningResource.IDENTITY_USER, () -> calls.add("identity-user"));

    attempt.complete();
    CompensationResult result = attempt.compensateIfIncomplete();

    assertThat(calls).isEmpty();
    assertThat(result.successful()).isTrue();
    assertThat(result.attemptedSteps()).isZero();
    assertThat(result.failedResources()).isEmpty();
    assertThat(meterRegistry.find(ProvisioningCompensator.ATTEMPT_METRIC).counter()).isNull();
  }

  @Test
  void failedStepDoesNotStopRemainingCompensationAndMeasuresPartialFailure() {
    List<String> calls = new ArrayList<>();
    ProvisioningAttempt attempt = compensator.begin(ProvisioningWorkflow.LEGACY_ASKER_WITH_SESSION);
    attempt.register(ProvisioningResource.IDENTITY_USER, () -> calls.add("identity-user"));
    attempt.register(
        ProvisioningResource.DATABASE_USER,
        () -> {
          calls.add("database-user");
          throw new IllegalStateException("sensitive failure details");
        });
    attempt.register(ProvisioningResource.SESSION, () -> calls.add("session"));

    CompensationResult result = attempt.compensateIfIncomplete();

    assertThat(calls).containsExactly("session", "database-user", "identity-user");
    assertThat(result.operationId()).isNotBlank();
    assertThat(result.successful()).isFalse();
    assertThat(result.attemptedSteps()).isEqualTo(3);
    assertThat(result.failedResources()).containsExactly(ProvisioningResource.DATABASE_USER);
    assertThat(
            meterRegistry
                .get(ProvisioningCompensator.ATTEMPT_METRIC)
                .tag("workflow", "legacy_asker_with_session")
                .tag("outcome", "partial_failure")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get(ProvisioningCompensator.STEP_METRIC)
                .tag("workflow", "legacy_asker_with_session")
                .tag("resource", "database_user")
                .tag("outcome", "failure")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void repeatedCompensationReturnsTheSameResultWithoutRepeatingSideEffects() {
    List<String> calls = new ArrayList<>();
    ProvisioningAttempt attempt =
        compensator.begin(ProvisioningWorkflow.LEGACY_ASKER_WITHOUT_SESSION);
    attempt.register(ProvisioningResource.IDENTITY_USER, () -> calls.add("identity-user"));

    CompensationResult firstResult = attempt.compensateIfIncomplete();
    CompensationResult replayResult = attempt.compensateIfIncomplete();

    assertThat(calls).containsExactly("identity-user");
    assertThat(replayResult).isEqualTo(firstResult);
    assertThat(
            meterRegistry
                .get(ProvisioningCompensator.ATTEMPT_METRIC)
                .tag("workflow", "legacy_asker_without_session")
                .tag("outcome", "success")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void failedStepLogsRepairIdentifierAndBoundedContextWithoutExceptionMessage() {
    ProvisioningAttempt attempt = compensator.begin(ProvisioningWorkflow.LEGACY_ASKER_WITH_SESSION);
    attempt.register(
        ProvisioningResource.DATABASE_USER,
        "db-user-42",
        () -> {
          throw new IllegalStateException("sensitive failure details");
        });

    try (LogbackCaptor logs = LogbackCaptor.forClass(ProvisioningAttempt.class)) {
      CompensationResult result = attempt.compensateIfIncomplete();

      assertThat(logs.messages(Level.WARN))
          .singleElement()
          .satisfies(
              message ->
                  assertThat(message)
                      .contains(
                          result.operationId(),
                          "legacy_asker_with_session",
                          "database_user",
                          "repairReference=db-user-42",
                          "IllegalStateException")
                      .doesNotContain("sensitive failure details"));
    }
  }

  @Test
  void repairIdentifierIsBoundedAndCannotInjectAnotherLogLine() {
    ProvisioningAttempt attempt = compensator.begin(ProvisioningWorkflow.REGISTERED_USER);
    attempt.register(
        ProvisioningResource.CHAT_IDENTITY,
        "chat-id\n" + "x".repeat(200),
        () -> {
          throw new IllegalStateException("failure");
        });

    try (LogbackCaptor logs = LogbackCaptor.forClass(ProvisioningAttempt.class)) {
      attempt.compensateIfIncomplete();

      assertThat(logs.messages(Level.WARN))
          .singleElement()
          .satisfies(
              message ->
                  assertThat(message)
                      .contains("repairReference=chat-id_x")
                      .doesNotContain("\n")
                      .hasSizeLessThan(400));
    }
  }
}
