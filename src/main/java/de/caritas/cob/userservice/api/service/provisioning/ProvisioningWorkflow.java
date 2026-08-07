package de.caritas.cob.userservice.api.service.provisioning;

import java.util.Locale;

public enum ProvisioningWorkflow {
  REGISTERED_USER,
  LEGACY_ASKER_WITHOUT_SESSION,
  LEGACY_ASKER_WITH_SESSION;

  String metricValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
