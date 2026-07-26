package de.caritas.cob.userservice.api.service.provisioning;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ProvisioningAttempt {

  private final MeterRegistry meterRegistry;
  private final ProvisioningWorkflow workflow;
  private final String operationId;
  private final List<CompensationStep> steps = new ArrayList<>();
  private boolean completed;
  private CompensationResult compensationResult;

  ProvisioningAttempt(
      MeterRegistry meterRegistry, ProvisioningWorkflow workflow, String operationId) {
    this.meterRegistry = meterRegistry;
    this.workflow = workflow;
    this.operationId = operationId;
  }

  public void register(ProvisioningResource resource, CompensationAction action) {
    steps.add(new CompensationStep(resource, action));
  }

  public void complete() {
    completed = true;
  }

  public CompensationResult compensateIfIncomplete() {
    if (compensationResult != null) {
      return compensationResult;
    }
    if (completed) {
      compensationResult = new CompensationResult(operationId, true, 0, List.of());
      return compensationResult;
    }

    List<ProvisioningResource> failedResources = new ArrayList<>();
    List<CompensationStep> reverseSteps = new ArrayList<>(steps);
    Collections.reverse(reverseSteps);

    for (CompensationStep step : reverseSteps) {
      try {
        step.action().compensate();
        recordStep(step.resource(), "success");
      } catch (Exception exception) {
        failedResources.add(step.resource());
        recordStep(step.resource(), "failure");
        log.warn(
            "Provisioning compensation failed operationId={} workflow={} resource={} exception={}",
            operationId,
            workflow.metricValue(),
            step.resource().metricValue(),
            exception.getClass().getSimpleName());
      }
    }

    String outcome = failedResources.isEmpty() ? "success" : "partial_failure";
    meterRegistry
        .counter(
            ProvisioningCompensator.ATTEMPT_METRIC,
            "workflow",
            workflow.metricValue(),
            "outcome",
            outcome)
        .increment();
    compensationResult =
        new CompensationResult(
            operationId,
            failedResources.isEmpty(),
            reverseSteps.size(),
            List.copyOf(failedResources));
    return compensationResult;
  }

  private void recordStep(ProvisioningResource resource, String outcome) {
    meterRegistry
        .counter(
            ProvisioningCompensator.STEP_METRIC,
            "workflow",
            workflow.metricValue(),
            "resource",
            resource.metricValue(),
            "outcome",
            outcome)
        .increment();
  }

  private record CompensationStep(ProvisioningResource resource, CompensationAction action) {}
}
