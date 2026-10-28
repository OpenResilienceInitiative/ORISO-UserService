package de.caritas.cob.userservice.api.service.chat;

import static org.apache.commons.collections4.CollectionUtils.isEmpty;

import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.facade.ChatConverter;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.service.agency.EffectiveAgencySettingsLookup;
import de.caritas.cob.userservice.tenantservice.generated.web.model.BooleanPermissionPolicy;
import de.caritas.cob.userservice.tenantservice.generated.web.model.PermissionPolicyMode;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Uses fresh canonical format policies and the AgencyService's already-effective format value.
 * Requirements win; recommendations permit a leaf choice. A missing canonical map retains the
 * legacy tenant/master hard cap. If AgencyService is unavailable, the tenant's decision stands
 * (US#1171); no cached or globally merged tenant settings are introduced.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroupChatFeatureGate {

  private final @NonNull TenantService tenantService;
  private final @NonNull EffectiveAgencySettingsLookup effectiveAgencySettingsLookup;

  @Value("${multitenancy.enabled:true}")
  private boolean multitenancy;

  public void requireEnabled(Consultant consultant, ChatDTO chatDTO) {
    RestrictedTenantDTO tenant = currentTenant(consultant);
    ConversationType format = ChatConverter.conversationTypeOf(chatDTO);
    FormatPolicy selection = formatPolicy(tenant, format);
    BooleanPermissionPolicy policy = selection == null ? null : selection.policy();
    boolean tenantEnabled =
        policy != null
            ? policy.getValue()
            : tenant.getSettings() != null
                && Boolean.TRUE.equals(tenant.getSettings().getFeatureGroupChatV2Enabled());
    if (!tenantEnabled && (policy == null || policy.getMode() == PermissionPolicyMode.ENFORCED)) {
      throw disabledForTenant();
    }

    Long agencyId = resolveAgencyId(chatDTO, consultant);
    if (agencyId == null) {
      requireFallbackEnabled(tenantEnabled);
      return;
    }
    Optional<Settings> settings;
    try {
      settings = effectiveAgencySettingsLookup.findEffectiveSettings(agencyId, tenant.getId());
    } catch (org.springframework.web.client.RestClientException agencyServiceFailure) {
      log.warn(
          "Could not read effective settings of agency {} from the AgencyService; the tenant "
              + "decision stands for this {} create request. Cause: {}",
          agencyId,
          format,
          agencyServiceFailure.getClass().getSimpleName());
      requireFallbackEnabled(tenantEnabled);
      return;
    }
    if (settings.isEmpty()) {
      requireFallbackEnabled(tenantEnabled);
      return;
    }
    if (policy != null && policy.getMode() == PermissionPolicyMode.ENFORCED) return;

    Settings effective = settings.get();
    if (policy == null && Boolean.FALSE.equals(effective.getFeatureGroupChatV2Enabled())) {
      throw new ForbiddenException("Group chats are disabled for agency " + agencyId);
    }
    Boolean enabled = formatFlag(effective, format, selection == null || !selection.exactFormat());
    if (enabled == null) {
      requireFallbackEnabled(tenantEnabled);
    } else if (!enabled) {
      throw new ForbiddenException(formatLabel(format) + " are disabled for agency " + agencyId);
    }
  }

  private RestrictedTenantDTO currentTenant(Consultant consultant) {
    if (consultant == null) throw disabledForTenant();
    Long tenantId = consultant.getTenantId();
    RestrictedTenantDTO tenant;
    if (tenantId != null) {
      tenant = tenantService.getRestrictedTenantDataFresh(tenantId);
    } else if (!multitenancy) {
      tenant = tenantService.getSingleTenancyTenantDataFresh();
    } else {
      throw disabledForTenant();
    }
    if (tenant == null
        || tenant.getId() == null
        || tenant.getId() <= 0
        || tenantId != null && !tenantId.equals(tenant.getId())) {
      throw disabledForTenant();
    }
    return tenant;
  }

  /** Exact modern keys win; only absent keys inherit the transitional V2 policy. */
  private FormatPolicy formatPolicy(RestrictedTenantDTO tenant, ConversationType format) {
    var policies = tenant.getPermissionPolicies();
    if (policies == null) return null;
    String key =
        format == ConversationType.SELF_HELP
            ? "featureSelfHelpGroupsEnabled"
            : "featureInternalGroupChatEnabled";
    boolean exact = policies.containsKey(key);
    if (!exact) key = "featureGroupChatV2Enabled";
    if (!policies.containsKey(key)) return null;
    var policy = policies.get(key);
    if (policy == null || policy.getValue() == null || policy.getMode() == null) {
      throw new ServiceUnavailableException("No complete tenant group-chat policy is available");
    }
    return new FormatPolicy(policy, exact);
  }

  private record FormatPolicy(BooleanPermissionPolicy policy, boolean exactFormat) {}

  private void requireFallbackEnabled(boolean enabled) {
    if (!enabled) throw disabledForTenant();
  }

  /** The format flag, falling back to the master flag when the format flag is unset. */
  private static Boolean formatFlag(
      Settings effective, ConversationType format, boolean useLegacyMaster) {
    Boolean flag =
        format == ConversationType.SELF_HELP
            ? effective.getFeatureSelfHelpGroupsEnabled()
            : effective.getFeatureInternalGroupChatEnabled();
    return flag != null || !useLegacyMaster ? flag : effective.getFeatureGroupChatV2Enabled();
  }

  private static String formatLabel(ConversationType format) {
    return format == ConversationType.SELF_HELP ? "Conversation circles" : "Internal group chats";
  }

  /**
   * Mirrors {@code CreateChatFacade#resolveAgencyId}: the explicitly requested agency, else the
   * consultant's first agency. Returns {@code null} instead of failing when there is none; the
   * facade raises its own error for that case right after the gate.
   */
  private static Long resolveAgencyId(ChatDTO chatDTO, Consultant consultant) {
    if (chatDTO != null && chatDTO.getAgencyId() != null) {
      return chatDTO.getAgencyId();
    }
    if (consultant == null || isEmpty(consultant.getConsultantAgencies())) {
      return null;
    }
    return consultant.getConsultantAgencies().iterator().next().getAgencyId();
  }

  private ForbiddenException disabledForTenant() {
    return new ForbiddenException("Self-help group chat is disabled for this tenant");
  }
}
