package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.model.TopicPermission;
import java.util.List;
import java.util.Optional;

/** What the invite rules need to know about an existing agency; one lookup per request. */
public interface AgencyFacts {

  /** Empty when AgencyService does not know the agency or hides it from the caller's tenant. */
  Optional<Agency> find(long agencyId);

  record Agency(
      Long id,
      Long tenantId,
      boolean deleted,
      List<Long> topicIds,
      TopicPermission defaultPermission) {

    public Agency {
      topicIds = topicIds == null ? List.of() : List.copyOf(topicIds);
    }
  }
}
