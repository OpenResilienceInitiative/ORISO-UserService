package de.caritas.cob.userservice.api.service.chat;

import static org.apache.commons.collections4.CollectionUtils.isEmpty;

import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.facade.ChatConverter;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.service.agency.EffectiveAgencySettingsLookup;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Enforces the group-chat activation at the backend boundary on two levels (US#1171, ADR-013):
 *
 * <ol>
 *   <li><b>Traeger (tenant):</b> {@code featureGroupChatV2Enabled} from the TenantService, fail
 *       closed, unchanged behaviour.
 *   <li><b>Beratungsstelle (agency):</b> the <em>effective</em> settings the AgencyService serves
 *       for the requested agency (Traeger AND Beratungsstelle combined, see {@link
 *       EffectiveAgencySettingsLookup}). {@code featureGroupChatV2Enabled} is the master over both
 *       formats; the format flag ({@code featureInternalGroupChatEnabled} for internal team chats,
 *       {@code featureSelfHelpGroupsEnabled} for conversation circles) falls back to the master
 *       when unset; {@code null} means no restriction. A Beratungsstelle can only restrict, never
 *       widen, so this level is checked <em>after</em> the Traeger level.
 * </ol>
 *
 * <p>If the AgencyService cannot be reached, the Traeger decision stands and the failure is logged
 * (acceptance criterion of US#1171: "behaves as today").
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroupChatFeatureGate {

  private final @NonNull TenantService tenantService;
  private final @NonNull EffectiveAgencySettingsLookup effectiveAgencySettingsLookup;

  /**
   * Refuses the create request unless both the Traeger and the requested Beratungsstelle allow the
   * requested group-chat format.
   *
   * @param consultant the calling counsellor
   * @param chatDTO the create request; its agency and format decide which effective flag applies
   * @throws ForbiddenException with a reason naming the level and the format that is switched off
   */
  public void requireEnabled(Consultant consultant, ChatDTO chatDTO) {
    requireTenantEnabled(consultant);
    requireAgencyEnabled(consultant, chatDTO);
  }

  private void requireTenantEnabled(Consultant consultant) {
    if (consultant == null || consultant.getTenantId() == null) {
      throw disabledForTenant();
    }

    var tenant = tenantService.getRestrictedTenantDataFresh(consultant.getTenantId());
    if (tenant == null
        || tenant.getSettings() == null
        || !Boolean.TRUE.equals(tenant.getSettings().getFeatureGroupChatV2Enabled())) {
      throw disabledForTenant();
    }
  }

  private void requireAgencyEnabled(Consultant consultant, ChatDTO chatDTO) {
    Long agencyId = resolveAgencyId(chatDTO, consultant);
    if (agencyId == null) {
      return;
    }
    var format = ChatConverter.conversationTypeOf(chatDTO);

    Optional<Settings> settings;
    try {
      settings = effectiveAgencySettingsLookup.findEffectiveSettings(agencyId);
    } catch (RuntimeException agencyServiceFailure) {
      log.warn(
          "Could not read effective settings of agency {} from the AgencyService; the tenant "
              + "decision stands for this {} create request. Cause: {}",
          agencyId,
          format,
          agencyServiceFailure.toString());
      return;
    }
    if (settings.isEmpty()) {
      return;
    }

    var effective = settings.get();
    if (Boolean.FALSE.equals(effective.getFeatureGroupChatV2Enabled())) {
      throw new ForbiddenException("Group chats are disabled for agency " + agencyId);
    }
    if (Boolean.FALSE.equals(formatFlag(effective, format))) {
      throw new ForbiddenException(formatLabel(format) + " are disabled for agency " + agencyId);
    }
  }

  /** The format flag, falling back to the master flag when the format flag is unset. */
  private static Boolean formatFlag(Settings effective, ConversationType format) {
    Boolean flag =
        format == ConversationType.SELF_HELP
            ? effective.getFeatureSelfHelpGroupsEnabled()
            : effective.getFeatureInternalGroupChatEnabled();
    return flag != null ? flag : effective.getFeatureGroupChatV2Enabled();
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
