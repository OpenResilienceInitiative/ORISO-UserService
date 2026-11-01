package de.caritas.cob.userservice.api.service.agencyinvitelink;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAnonymousEnquiryDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAnonymousEnquiryResponseDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.conversation.facade.CreateAnonymousEnquiryFacade;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.ConsultingTypeService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Manages one-time invite links. On redeem, the link is marked used and metadata is returned so the
 * frontend can run the standard anonymous asker registration (same flow as before the topic-based
 * redesign).
 */
@Service
@RequiredArgsConstructor
public class AgencyInviteLinkService {

  private static final int TOKEN_BYTES = 24;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final @NonNull AgencyInviteLinkRepository repository;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull TopicService topicService;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ConsultingTypeService consultingTypeService;
  private final @NonNull AgencyService agencyService;
  private final @NonNull CreateAnonymousEnquiryFacade createAnonymousEnquiryFacade;
  private final @NonNull AdminScope adminScope;
  private final @NonNull de.caritas.cob.userservice.api.admin.service.tenant.TenantService
      tenantService;
  private final @NonNull de.caritas.cob.userservice.api.service.temporaryaccount
          .TemporaryAccountSettingsService
      temporaryAccountSettings;

  /** Call share links (#1487) in the public context; not {@code @NonNull} for older test wiring. */
  private final CallShareLinkService callShareLinkService;

  /** Create a new invite link. All classification fields are optional — defaults are applied. */
  public AgencyInviteLink create(CreateInviteLinkCommand cmd) {
    if (cmd == null) {
      throw new BadRequestException("Request body is required");
    }
    applyDefaults(cmd);
    validateEnums(cmd);
    if (InviteLinkKind.CALL.name().equals(cmd.getLinkKind())) {
      // Only a counsellor in a running call creates its share link (#1487).
      throw new BadRequestException("Call share links are created from the call");
    }

    if (InviteLinkKind.COUNSELLOR.name().equals(cmd.getLinkKind())
        && isBlank(cmd.getConsultantId())) {
      throw new BadRequestException("consultantId is required when linkKind = COUNSELLOR");
    }

    Long callerTenantId = resolveCallerTenantId();
    if (callerTenantId == null) {
      throw new ForbiddenException("No tenant context — caller must be tenant-scoped");
    }

    Integer consultingTypeId = null;
    if (cmd.getAgencyId() != null) {
      AgencyDTO agency = agencyService.getAgencyWithoutCaching(cmd.getAgencyId());
      if (agency == null) {
        throw new NotFoundException("Agency %s not found", cmd.getAgencyId());
      }
      if (!Objects.equals(agency.getTenantId(), callerTenantId)) {
        throw new ForbiddenException("Agency is outside caller's tenant");
      }
      consultingTypeId = agency.getConsultingType();
    }

    if (cmd.getTopicId() != null) {
      TopicDTO topic = topicService.getTopicById(cmd.getTopicId());
      if (topic == null) {
        throw new NotFoundException("Topic %s not found", cmd.getTopicId());
      }
    }

    if (InviteLinkKind.COUNSELLOR.name().equals(cmd.getLinkKind())) {
      validateConsultantInTenant(cmd.getConsultantId(), callerTenantId);
    }
    adminScope.assertMay(Target.placedIn(callerTenantId, cmd.getAgencyId()));
    if (InviteLinkKind.COUNSELLOR.name().equals(cmd.getLinkKind())) {
      adminScope.assertMay(Target.counsellor(cmd.getConsultantId()));
    }

    LocalDateTime now = LocalDateTime.now();
    AgencyInviteLink link =
        AgencyInviteLink.builder()
            .token(generateToken())
            .tenantId(callerTenantId)
            .agencyId(cmd.getAgencyId())
            .consultingTypeId(consultingTypeId)
            .topicId(cmd.getTopicId())
            .linkKind(cmd.getLinkKind())
            .chatType(cmd.getChatType())
            .anonymity(cmd.getAnonymity())
            .notes(cmd.getNotes())
            .consultantId(
                InviteLinkKind.COUNSELLOR.name().equals(cmd.getLinkKind())
                    ? cmd.getConsultantId()
                    : null)
            .createdByUserId(authenticatedUser.getUserId())
            .createdByUsername(authenticatedUser.getUsername())
            .createDate(now)
            .expiresAt(
                cmd.getExpiresInDays() != null && cmd.getExpiresInDays() > 0
                    ? now.plusDays(cmd.getExpiresInDays())
                    : null)
            .status(InviteLinkStatus.ACTIVE.name())
            .build();
    return repository.save(link);
  }

  /** List invite links for the caller's tenant, optionally filtered and paged. */
  public Page<AgencyInviteLink> list(
      String linkKind, Long topicId, String chatType, String status, int page, int size) {

    if (linkKind != null && !isValidEnumValue(linkKind, InviteLinkKind.class)) {
      throw new BadRequestException("Unknown linkKind: " + linkKind);
    }
    if (InviteLinkKind.CALL.name().equals(linkKind)) {
      // Call share links are never listed for admins (#1487).
      throw new BadRequestException("Call share links are not listed");
    }
    if (chatType != null && !isValidEnumValue(chatType, InviteLinkChatType.class)) {
      throw new BadRequestException("Unknown chatType: " + chatType);
    }
    if (status != null && !isValidEnumValue(status, InviteLinkStatus.class)) {
      throw new BadRequestException("Unknown status: " + status);
    }

    Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
    Long callerTenantId = resolveCallerTenantId();
    if (callerTenantId == null) {
      return Page.empty(pageable);
    }

    var reach = adminScope.current();
    Page<AgencyInviteLink> result;
    if (reach instanceof AdminScope.Agencies agencies) {
      if (agencies.ids().isEmpty()) {
        return Page.empty(pageable);
      }
      result =
          repository.findAllByTenantIdAndAgencyIdsAndFilters(
              callerTenantId, agencies.ids(), linkKind, topicId, chatType, status, pageable);
    } else {
      result =
          repository.findAllByTenantIdAndFilters(
              callerTenantId, linkKind, topicId, chatType, status, pageable);
    }

    // Only auto-expire when no status filter was requested, to avoid returning EXPIRED
    // rows to a caller who explicitly asked for ACTIVE.
    if (status == null) {
      result.getContent().forEach(this::autoExpireIfNeeded);
    }
    return result;
  }

  /**
   * Public entry metadata of any link (the {@code /context} route): a call share link (#1487) has
   * its own, smaller shape.
   */
  @Transactional(readOnly = true)
  public Object getPublicContext(String token) {
    AgencyInviteLink link =
        repository
            .findByToken(token)
            .orElseThrow(() -> new NotFoundException("Invite link not found"));
    return InviteLinkKind.CALL.name().equals(link.getLinkKind())
        ? callShareContext(link)
        : contextOf(link);
  }

  /** Read public entry metadata without provisioning a session or changing the link. */
  @Transactional(readOnly = true)
  public InvitationContext getContext(String token) {
    AgencyInviteLink link =
        repository
            .findByToken(token)
            .orElseThrow(() -> new NotFoundException("Invite link not found"));
    if (InviteLinkKind.CALL.name().equals(link.getLinkKind())) {
      throw new BadRequestException("Not a registration link");
    }
    return contextOf(link);
  }

  private InvitationContext contextOf(AgencyInviteLink link) {
    if (InviteLinkStatus.DISABLED.name().equals(link.getStatus())
        && InviteLinkChatType.isDirectMedium(link.getChatType())) {
      throw new DirectLinkInactiveException();
    }
    if (!InviteLinkStatus.ACTIVE.name().equals(link.getStatus())) {
      throw new BadRequestException("Invite link is not active");
    }
    if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(LocalDateTime.now())) {
      throw new BadRequestException("Invite link expired");
    }
    TenantData previousTenant = TenantContext.getCurrentTenantData();
    try {
      TenantContext.setCurrentTenantData(new TenantData(link.getTenantId(), null));
      // The owner lookup is tenant-filtered and this route runs without a tenant.
      DirectLinkOwner.requireStillServing(consultantRepository, link);
      Integer consultingTypeId = pickConsultingTypeId(link);
      Long agencyId =
          InviteLinkChatType.LIVE_CHAT.name().equals(link.getChatType())
              ? null
              : resolveAgencyIdForRegistration(link, consultingTypeId);
      boolean direct = InviteLinkChatType.isDirectMedium(link.getChatType());
      // A link that creates temporary accounts tells the registration page what applies to
      // people who join now (ADR-029 §3–§5, #1476).
      var settings =
          direct && link.isTemporaryAccount() ? temporaryAccountSettings.current() : null;
      return new InvitationContext(
          link.getTenantId(),
          agencyId,
          consultingTypeId,
          link.getTopicId(),
          link.getChatType(),
          direct ? link.isTemporaryAccount() : null,
          direct ? InviteLinkChatType.valueOf(link.getChatType()).admission().name() : null,
          settings == null ? null : settings.hidePasswordField(),
          settings == null ? null : settings.messageRetention().name());
    } finally {
      if (previousTenant == null) {
        TenantContext.clear();
      } else {
        TenantContext.setCurrentTenantData(previousTenant);
      }
    }
  }

  /**
   * Call share link (#1487): 410 once the call is over. {@code temporaryAccount} is effective (the
   * counsellor's setting, and allowed by platform and Träger); {@code admission} follows
   * "Anklopfen" and changes while the call runs. Beratungsstelle, consulting type and topic stay in
   * the context for the entry room's consent and registration (decision pending, #1487); nothing
   * about the call or its conversation is exposed.
   */
  private CallShareContext callShareContext(AgencyInviteLink link) {
    callShareLinkService.requireUsable(link);
    return new CallShareContext(
        link.getTenantId(),
        link.getAgencyId(),
        link.getConsultingTypeId(),
        link.getTopicId(),
        link.getChatType(),
        link.isTemporaryAccount() && callShareLinkService.temporaryAccountAllowed(link),
        (link.isKnock() ? InviteLinkAdmission.KNOCK : InviteLinkAdmission.DIRECT).name(),
        InviteLinkKind.CALL.name());
  }

  /** Public context of a call share link (#1487). */
  public record CallShareContext(
      Long tenantId,
      Long agencyId,
      Integer consultingTypeId,
      Long topicId,
      String chatType,
      boolean temporaryAccount,
      String admission,
      String linkKind) {}

  /**
   * Public context deliberately excludes credentials and invitation administration data, including
   * the owner of a direct link. The direct-link fields are omitted for every other link.
   */
  public record InvitationContext(
      Long tenantId,
      Long agencyId,
      Integer consultingTypeId,
      Long topicId,
      String chatType,
      @JsonInclude(JsonInclude.Include.NON_NULL) Boolean temporaryAccount,
      @JsonInclude(JsonInclude.Include.NON_NULL) String admission,
      @JsonInclude(JsonInclude.Include.NON_NULL) Boolean hidePasswordField,
      @JsonInclude(JsonInclude.Include.NON_NULL) String messageRetention) {}

  /**
   * Redeem the token: validate, mark USED, return tenant/agency/consulting-type/topic metadata for
   * the frontend registration flow. Does not create Keycloak or Matrix users on the server.
   */
  @Transactional
  public RedeemContext redeemWithContext(String token) {
    return redeem(token);
  }

  @Transactional
  RedeemContext redeem(String token) {
    AgencyInviteLink link =
        repository
            .findByTokenAndStatus(token, InviteLinkStatus.ACTIVE.name())
            .orElseGet(() -> null);

    if (link == null) {
      AgencyInviteLink existing =
          repository
              .findByToken(token)
              .orElseThrow(() -> new NotFoundException("Invite link not found"));
      if (InviteLinkStatus.USED.name().equals(existing.getStatus())) {
        throw new BadRequestException("Invite link already used");
      }
      if (InviteLinkStatus.DISABLED.name().equals(existing.getStatus())
          && InviteLinkChatType.isDirectMedium(existing.getChatType())) {
        throw new DirectLinkInactiveException();
      }
      throw new BadRequestException("Invite link is not active");
    }

    if (InviteLinkKind.CALL.name().equals(link.getLinkKind())) {
      // A call share link opens one running call only (#1487), never a registration context.
      throw new BadRequestException("Not a registration link");
    }
    if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(LocalDateTime.now())) {
      link.setStatus(InviteLinkStatus.EXPIRED.name());
      repository.save(link);
      throw new BadRequestException("Invite link expired");
    }
    tenantService.requireOperationalTenant(link.getTenantId());

    Long previousTenant = TenantContext.getCurrentTenant();
    try {
      TenantContext.setCurrentTenant(link.getTenantId());
      // The owner lookup is tenant-filtered and this route runs without a tenant.
      DirectLinkOwner.requireStillServing(consultantRepository, link);

      Integer consultingTypeId = pickConsultingTypeId(link);

      // A Live Chat link binds only to a topic. Redeem creates a real anonymous session for the
      // link's topic and returns it directly — no agency/tenant binding at entry. The client
      // enters the cross-agency/cross-tenant topic queue; binding to a concrete agency/tenant and
      // that agency's data-protection declaration happen only when a live consultant accepts.
      if (InviteLinkChatType.LIVE_CHAT.name().equals(link.getChatType())) {
        var dto = new CreateAnonymousEnquiryDTO(consultingTypeId).mainTopicId(link.getTopicId());
        var session = createAnonymousEnquiryFacade.createAnonymousEnquiry(dto, true);
        // Public Live Chat entry points are reusable until expiry so multiple clients can enter
        // the shared topic queue through the same published link.
        return new RedeemContext(
            session, link.getTenantId(), null, consultingTypeId, link.getTopicId());
      }

      // Registered (non-live-chat) links keep the legacy client-side registration path.
      Long agencyId = resolveAgencyIdForRegistration(link, consultingTypeId);

      // Link is reusable until expiry date — stay ACTIVE, do not mark USED.

      return new RedeemContext(
          null, link.getTenantId(), agencyId, consultingTypeId, link.getTopicId());
    } finally {
      if (previousTenant == null) {
        TenantContext.clear();
      } else {
        TenantContext.setCurrentTenant(previousTenant);
      }
    }
  }

  private Long resolveAgencyIdForRegistration(AgencyInviteLink link, Integer consultingTypeId) {
    if (link.getAgencyId() != null) {
      return link.getAgencyId();
    }
    List<AgencyDTO> agencies = agencyService.getAgenciesByConsultingType(consultingTypeId);
    if (agencies == null || agencies.isEmpty()) {
      throw new BadRequestException(
          "No agency available for this invite link — set agencyId on the link or configure a topic fallback agency");
    }
    return agencies.stream()
        .filter(agency -> Objects.equals(agency.getTenantId(), link.getTenantId()))
        .filter(
            agency ->
                link.getTopicId() == null
                    || (agency.getTopicIds() != null
                        && agency.getTopicIds().contains(link.getTopicId())))
        .map(AgencyDTO::getId)
        .findFirst()
        .orElseThrow(
            () ->
                new BadRequestException(
                    "No agency in the invite link tenant serves the configured consulting type and topic"));
  }

  // ---------------------------------------------------------------------------------------------

  private void applyDefaults(CreateInviteLinkCommand cmd) {
    if (isBlank(cmd.getLinkKind())) cmd.setLinkKind(InviteLinkKind.EXTERNAL_INBOUND.name());
    if (isBlank(cmd.getChatType())) cmd.setChatType(InviteLinkChatType.LIVE_CHAT.name());
    if (isBlank(cmd.getAnonymity())) cmd.setAnonymity(InviteLinkAnonymity.FULL.name());
  }

  private void validateEnums(CreateInviteLinkCommand cmd) {
    if (!isValidEnumValue(cmd.getLinkKind(), InviteLinkKind.class)) {
      throw new BadRequestException(
          "linkKind must be one of " + Arrays.toString(InviteLinkKind.values()));
    }
    // Direct links (CHAT/AUDIO/VIDEO) belong to a counsellor: /users/directlinks (#1475).
    if (!InviteLinkChatType.LIVE_CHAT.name().equals(cmd.getChatType())) {
      throw new BadRequestException("chatType must be LIVE_CHAT");
    }
    if (!isValidEnumValue(cmd.getAnonymity(), InviteLinkAnonymity.class)) {
      throw new BadRequestException(
          "anonymity must be one of " + Arrays.toString(InviteLinkAnonymity.values()));
    }
    if (cmd.getNotes() != null && cmd.getNotes().length() > 500) {
      throw new BadRequestException("notes cannot exceed 500 characters");
    }
    if (cmd.getExpiresInDays() != null
        && (cmd.getExpiresInDays() < 1 || cmd.getExpiresInDays() > 365)) {
      throw new BadRequestException("expiresInDays must be between 1 and 365 (or null = never)");
    }
  }

  private void validateConsultantInTenant(String consultantId, Long callerTenantId) {
    var consultant =
        consultantRepository
            .findByIdAndDeleteDateIsNull(consultantId)
            .orElseThrow(() -> new NotFoundException("Consultant %s not found", consultantId));
    if (!Objects.equals(consultant.getTenantId(), callerTenantId)) {
      throw new ForbiddenException("Consultant is outside caller's tenant");
    }
  }

  private Integer pickConsultingTypeId(AgencyInviteLink link) {
    if (link.getConsultingTypeId() != null) {
      return link.getConsultingTypeId();
    }
    List<Integer> ids = consultingTypeService.getAllConsultingTypeIds(link.getTenantId());
    if (ids == null || ids.isEmpty()) {
      throw new InternalServerErrorException(
          "No consulting types configured for tenant " + link.getTenantId());
    }
    return ids.get(0);
  }

  private AgencyInviteLink autoExpireIfNeeded(AgencyInviteLink link) {
    if (InviteLinkStatus.ACTIVE.name().equals(link.getStatus())
        && link.getExpiresAt() != null
        && link.getExpiresAt().isBefore(LocalDateTime.now())) {
      link.setStatus(InviteLinkStatus.EXPIRED.name());
      repository.save(link);
    }
    return link;
  }

  /** The platform picks the Träger with the X-Tenant-Id header; everybody else has their own. */
  private Long resolveCallerTenantId() {
    var reach = adminScope.current();
    return reach instanceof AdminScope.Platform ? resolveTenantFromHeader() : reach.tenantId();
  }

  private Long resolveTenantFromHeader() {
    try {
      ServletRequestAttributes attrs =
          (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
      if (attrs == null) return null;
      HttpServletRequest request = attrs.getRequest();
      String header = request.getHeader("X-Tenant-Id");
      if (header == null || header.isBlank()) return null;
      return Long.parseLong(header.trim());
    } catch (Exception ex) {
      return null;
    }
  }

  private String generateToken() {
    byte[] buf = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(buf);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
  }

  private static <E extends Enum<E>> boolean isValidEnumValue(String value, Class<E> cls) {
    try {
      Enum.valueOf(cls, value);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }

  private static int clampSize(int size) {
    if (size < 1) {
      return 20;
    }
    return Math.min(size, 100);
  }

  /** Service-layer carrier for a create request. */
  public static class CreateInviteLinkCommand {
    private Long agencyId;
    private Long topicId;
    private String linkKind;
    private String chatType;
    private String anonymity;
    private String consultantId;
    private String notes;
    private Long expiresInDays;

    public Long getAgencyId() {
      return agencyId;
    }

    public void setAgencyId(Long agencyId) {
      this.agencyId = agencyId;
    }

    public Long getTopicId() {
      return topicId;
    }

    public void setTopicId(Long topicId) {
      this.topicId = topicId;
    }

    public String getLinkKind() {
      return linkKind;
    }

    public void setLinkKind(String linkKind) {
      this.linkKind = linkKind;
    }

    public String getChatType() {
      return chatType;
    }

    public void setChatType(String chatType) {
      this.chatType = chatType;
    }

    public String getAnonymity() {
      return anonymity;
    }

    public void setAnonymity(String anonymity) {
      this.anonymity = anonymity;
    }

    public String getConsultantId() {
      return consultantId;
    }

    public void setConsultantId(String consultantId) {
      this.consultantId = consultantId;
    }

    public String getNotes() {
      return notes;
    }

    public void setNotes(String notes) {
      this.notes = notes;
    }

    public Long getExpiresInDays() {
      return expiresInDays;
    }

    public void setExpiresInDays(Long expiresInDays) {
      this.expiresInDays = expiresInDays;
    }
  }

  /** Redeem metadata for the controller response (session is null for legacy redeem). */
  public static class RedeemContext {
    private final CreateAnonymousEnquiryResponseDTO session;
    private final Long tenantId;
    private final Long agencyId;
    private final Integer consultingTypeId;
    private final Long topicId;

    public RedeemContext(
        CreateAnonymousEnquiryResponseDTO session,
        Long tenantId,
        Long agencyId,
        Integer consultingTypeId,
        Long topicId) {
      this.session = session;
      this.tenantId = tenantId;
      this.agencyId = agencyId;
      this.consultingTypeId = consultingTypeId;
      this.topicId = topicId;
    }

    public CreateAnonymousEnquiryResponseDTO getSession() {
      return session;
    }

    public Long getTenantId() {
      return tenantId;
    }

    public Long getAgencyId() {
      return agencyId;
    }

    public Integer getConsultingTypeId() {
      return consultingTypeId;
    }

    public Long getTopicId() {
      return topicId;
    }
  }
}
