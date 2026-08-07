package de.caritas.cob.userservice.api.service.provisioning;

import java.util.List;

public record CompensationResult(
    String operationId,
    boolean successful,
    int attemptedSteps,
    List<ProvisioningResource> failedResources) {}
