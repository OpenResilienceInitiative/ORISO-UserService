package de.caritas.cob.userservice.api.service.provisioning;

import java.util.Locale;

public enum ProvisioningResource {
  IDENTITY_USER,
  DATABASE_USER,
  SESSION,
  GROUP_CHAT_MEMBERSHIP,
  USER_AGENCY,
  CHAT_IDENTITY,
  LEGACY_CHAT_USER,
  LEGACY_CHAT_GROUP;

  String metricValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
