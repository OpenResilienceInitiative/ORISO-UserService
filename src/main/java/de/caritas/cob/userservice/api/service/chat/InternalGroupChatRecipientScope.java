package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who a counsellor may pick for an internal group chat, and who may see and join an ordinary one:
 * the Träger setting {@code internalGroupChat.recipientScope} of the TenantService (ORISO-
 * TenantService#319). The narrowest value, {@link #AGENCY}, is the behaviour before the setting
 * existed and the default whenever the setting is missing or unreadable.
 */
public enum InternalGroupChatRecipientScope {
  /** Colleagues of the same counselling centre (Beratungsstelle) and the same Träger. */
  AGENCY {
    @Override
    public boolean permits(
        Consultant creator, Consultant colleague, Long agencyId, boolean multitenancy) {
      if (!sameTenant(creator, colleague, multitenancy)) {
        return false;
      }
      if (agencyId != null) {
        return creator.isInAgency(agencyId) && colleague.isInAgency(agencyId);
      }
      return !Collections.disjoint(agencyIdsOf(creator), agencyIdsOf(colleague));
    }
  },
  /** Every colleague of the same Träger, whatever the counselling centre. */
  TENANT {
    @Override
    public boolean permits(
        Consultant creator, Consultant colleague, Long agencyId, boolean multitenancy) {
      return sameTenant(creator, colleague, multitenancy);
    }
  },
  /** Every colleague of the platform, whatever the Träger. */
  PLATFORM {
    @Override
    public boolean permits(
        Consultant creator, Consultant colleague, Long agencyId, boolean multitenancy) {
      return true;
    }
  };

  private static final String SETTING = "internalGroupChat";
  private static final String SCOPE = "recipientScope";

  /**
   * Whether {@code creator} may pick {@code colleague}.
   *
   * @param agencyId the counselling centre the chat is created for; {@code null} when none is named
   *     (then any centre the two share counts)
   */
  public abstract boolean permits(
      Consultant creator, Consultant colleague, Long agencyId, boolean multitenancy);

  /** {@link #permits(Consultant, Consultant, Long, boolean)} with multitenancy enforced. */
  public boolean permits(Consultant creator, Consultant colleague, Long agencyId) {
    return permits(creator, colleague, agencyId, true);
  }

  /**
   * Reads the resolved scope from the raw {@code GET /tenantadmin/{id}/permission-policies} body.
   * The field is read leniently on purpose: a TenantService that does not know the setting yet, a
   * missing field or an unknown value all mean {@link #AGENCY}.
   */
  public static InternalGroupChatRecipientScope fromPolicies(Map<String, Object> policies) {
    if (policies != null
        && policies.get(SETTING) instanceof Map<?, ?> setting
        && setting.get(SCOPE) instanceof Map<?, ?> scope
        && scope.get("value") instanceof String value) {
      for (var candidate : values()) {
        if (candidate.name().equals(value)) {
          return candidate;
        }
      }
    }
    return AGENCY;
  }

  /**
   * With multitenancy an unknown tenant never matches another one. Without it consultants carry no
   * tenant at all, and "same Träger" is then simply "same stored value" ({@link
   * GroupChatConsultantAccess} applies the same rule to a chat's owner).
   */
  private static boolean sameTenant(
      Consultant creator, Consultant colleague, boolean multitenancy) {
    if (multitenancy && creator.getTenantId() == null) {
      return false;
    }
    return Objects.equals(creator.getTenantId(), colleague.getTenantId());
  }

  private static Set<Long> agencyIdsOf(Consultant consultant) {
    if (consultant.getConsultantAgencies() == null) {
      return Set.of();
    }
    return consultant.getConsultantAgencies().stream()
        .map(ConsultantAgency::getAgencyId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }
}
