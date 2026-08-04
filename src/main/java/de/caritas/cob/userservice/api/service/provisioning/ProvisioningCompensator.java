package de.caritas.cob.userservice.api.service.provisioning;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProvisioningCompensator {

  public static final String ATTEMPT_METRIC = "userservice.provisioning.compensation.attempts";
  public static final String STEP_METRIC = "userservice.provisioning.compensation.steps";

  private final MeterRegistry meterRegistry;

  public ProvisioningAttempt begin(ProvisioningWorkflow workflow) {
    return new ProvisioningAttempt(meterRegistry, workflow, UUID.randomUUID().toString());
  }
}
