package de.caritas.cob.userservice.api.port.out;

import java.util.UUID;

/** Keyed data binding for a locally issued magic-link workflow; it grants no authority. */
public interface MagicLinkCapabilityDigest {
  String digest(UUID workflowId, String oneTimeToken);
}
