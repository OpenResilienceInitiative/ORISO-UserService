package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateAnonymousEnquiryDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAnonymousEnquiryResponseDTO;
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
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages one-time invite links. Each link is bound to a topic (not an agency); when redeemed it
 * creates an anonymous enquiry session via {@link CreateAnonymousEnquiryFacade}. Agency is no
 * longer carried on the link — it gets attached to the resulting session later, when a counsellor
 * accepts the enquiry.
 */
@Service
@RequiredArgsConstructor
public class AgencyInviteLinkService {

  static final String LINK_KIND_TENANT = "TENANT";
  static final String LINK_KIND_COUNSELLOR = "COUNSELLOR";
  static final String LINK_KIND_EXTERNAL_INBOUND = "EXTERNAL_INBOUND";
  private static final Set<String> ALLOWED_LINK_KINDS =
      Set.of(LINK_KIND_TENANT, LINK_KIND_COUNSELLOR, LINK_KIND_EXTERNAL_INBOUND);

  static final String CHAT_TYPE_LIVE_CHAT = "LIVE_CHAT";
  private static final Set<String> ALLOWED_CHAT_TYPES = Set.of(CHAT_TYPE_LIVE_CHAT);

  static final String ANONYMITY_FULL = "FULL";
  private static final Set<String> ALLOWED_ANONYMITY = Set.of(ANONYMITY_FULL);

  static final String STATUS_ACTIVE = "ACTIVE";
  static final String STATUS_USED = "USED";
  static final String STATUS_EXPIRED = "EXPIRED";
  private static final Set<String> ALLOWED_STATUSES = Set.of(STATUS_ACTIVE, STATUS_USED, STATUS_EXPIRED);

  private static final int TOKEN_BYTES = 24;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final @NonNull AgencyInviteLinkRepository repository;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull TopicService topicService;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ConsultingTypeService consultingTypeService;
  private final @NonNull CreateAnonymousEnquiryFacade createAnonymousEnquiryFacade;

  /** Create a new topic-based invite link. */
  public AgencyInviteLink create(CreateInviteLinkCommand cmd) {
    if (cmd == null) {
      throw new BadRequestException("Request body is required");
    }
    validateEnums(cmd);
    if (cmd.getTopicId() == null) {
      throw new BadRequestException("topicId is required");
    }
    if (LINK_KIND_COUNSELLOR.equals(cmd.getLinkKind()) && isBlank(cmd.getConsultantId())) {
      throw new BadRequestException("consultantId is required when linkKind = COUNSELLOR");
    }

    Long callerTenantId = resolveCallerTenantId();
    if (callerTenantId == null) {
      throw new ForbiddenException("No tenant context — caller must be tenant-scoped");
    }

    TopicDTO topic = topicService.getTopicById(cmd.getTopicId());
    if (topic == null) {
      // Topic does not exist in the caller's tenant scope. Returning 404 also covers cross-tenant
      // access attempts without leaking whether the topic exists in another tenant.
      throw new NotFoundException("Topic %s not found", cmd.getTopicId());
    }

    if (LINK_KIND_COUNSELLOR.equals(cmd.getLinkKind())) {
      validateConsultantInTenant(cmd.getConsultantId(), callerTenantId);
    }

    LocalDateTime now = LocalDateTime.now();
    AgencyInviteLink link =
        AgencyInviteLink.builder()
            .token(generateToken())
            .tenantId(callerTenantId)
            .topicId(cmd.getTopicId())
            .linkKind(cmd.getLinkKind())
            .chatType(cmd.getChatType())
            .anonymity(cmd.getAnonymity())
            .notes(cmd.getNotes())
            .consultantId(LINK_KIND_COUNSELLOR.equals(cmd.getLinkKind()) ? cmd.getConsultantId() : null)
            .createdByUserId(authenticatedUser.getUserId())
            .createdByUsername(authenticatedUser.getUsername())
            .createDate(now)
            .expiresAt(cmd.getExpiresInDays() != null && cmd.getExpiresInDays() > 0
                ? now.plusDays(cmd.getExpiresInDays())
                : null)
            .status(STATUS_ACTIVE)
            .build();
    return repository.save(link);
  }

  /** List invite links for the caller's tenant, optionally filtered and paged. */
  public Page<AgencyInviteLink> list(
      String linkKind,
      Long topicId,
      String chatType,
      String status,
      int page,
      int size) {

    if (linkKind != null && !ALLOWED_LINK_KINDS.contains(linkKind)) {
      throw new BadRequestException("Unknown linkKind: " + linkKind);
    }
    if (chatType != null && !ALLOWED_CHAT_TYPES.contains(chatType)) {
      throw new BadRequestException("Unknown chatType: " + chatType);
    }
    if (status != null && !ALLOWED_STATUSES.contains(status)) {
      throw new BadRequestException("Unknown status: " + status);
    }

    Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
    Long callerTenantId = resolveCallerTenantId();
    if (callerTenantId == null) {
      // No tenant context — return empty rather than crossing tenants.
      return Page.empty(pageable);
    }

    Page<AgencyInviteLink> result =
        repository.findAllByTenantIdAndFilters(
            callerTenantId, linkKind, topicId, chatType, status, pageable);

    // Lazy-expire active links whose `expires_at` has passed, so the UI sees fresh status.
    result.getContent().forEach(this::autoExpireIfNeeded);
    return result;
  }

  /**
   * Redeem the token. Validates and locks the row, creates an anonymous-enquiry session via the
   * existing facade, then marks the link {@code USED}. Returns the rich session payload the
   * frontend needs to drop the user straight into the chat.
   */
  @Transactional
  public CreateAnonymousEnquiryResponseDTO redeem(String token) {
    AgencyInviteLink link =
        repository
            .findByTokenAndStatus(token, STATUS_ACTIVE)
            .orElseGet(() -> null);

    if (link == null) {
      // Distinguish "expired/used" from "not found" for clearer client UX.
      AgencyInviteLink existing =
          repository
              .findByToken(token)
              .orElseThrow(() -> new NotFoundException("Invite link not found"));
      if (STATUS_USED.equals(existing.getStatus())) {
        throw new BadRequestException("Invite link already used");
      }
      throw new BadRequestException("Invite link is not active");
    }

    if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(LocalDateTime.now())) {
      link.setStatus(STATUS_EXPIRED);
      repository.save(link);
      throw new BadRequestException("Invite link expired");
    }

    if (link.getTopicId() == null) {
      // Should not happen for links produced by the new flow; legacy rows without a topic cannot
      // be redeemed because the anonymous-enquiry facade needs a topic to tag the session.
      throw new BadRequestException("Invite link has no topic — cannot redeem");
    }

    // The redeem endpoint is public — no tenant header on the request. Set the tenant context
    // from the link so downstream service clients (consulting-type, agency, etc.) see the right
    // tenant for header propagation.
    Long previousTenant = TenantContext.getCurrentTenant();
    try {
      TenantContext.setCurrentTenant(link.getTenantId());

      Integer consultingTypeId = pickTenantDefaultConsultingType(link.getTenantId());

      CreateAnonymousEnquiryDTO dto = new CreateAnonymousEnquiryDTO();
      dto.setConsultingType(consultingTypeId);
      dto.setMainTopicId(link.getTopicId());
      if (LINK_KIND_COUNSELLOR.equals(link.getLinkKind())) {
        dto.setConsultantId(link.getConsultantId());
      }

      CreateAnonymousEnquiryResponseDTO response =
          createAnonymousEnquiryFacade.createAnonymousEnquiry(dto);

      link.setStatus(STATUS_USED);
      link.setUsedAt(LocalDateTime.now());
      link.setUsedBySessionId(response.getSessionId());
      repository.save(link);

      return response;
    } finally {
      if (previousTenant == null) {
        TenantContext.clear();
      } else {
        TenantContext.setCurrentTenant(previousTenant);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------

  private void validateEnums(CreateInviteLinkCommand cmd) {
    if (!ALLOWED_LINK_KINDS.contains(cmd.getLinkKind())) {
      throw new BadRequestException("linkKind must be one of " + ALLOWED_LINK_KINDS);
    }
    if (!ALLOWED_CHAT_TYPES.contains(cmd.getChatType())) {
      throw new BadRequestException("chatType must be one of " + ALLOWED_CHAT_TYPES);
    }
    if (!ALLOWED_ANONYMITY.contains(cmd.getAnonymity())) {
      throw new BadRequestException("anonymity must be one of " + ALLOWED_ANONYMITY);
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

  private Integer pickTenantDefaultConsultingType(Long tenantId) {
    List<Integer> ids = consultingTypeService.getAllConsultingTypeIds(tenantId);
    if (ids == null || ids.isEmpty()) {
      throw new InternalServerErrorException(
          "No consulting types configured for tenant " + tenantId);
    }
    return ids.get(0);
  }

  private AgencyInviteLink autoExpireIfNeeded(AgencyInviteLink link) {
    if (STATUS_ACTIVE.equals(link.getStatus())
        && link.getExpiresAt() != null
        && link.getExpiresAt().isBefore(LocalDateTime.now())) {
      link.setStatus(STATUS_EXPIRED);
      repository.save(link);
    }
    return link;
  }

  private Long resolveCallerTenantId() {
    try {
      Long tenantId = TenantContext.getCurrentTenant();
      if (tenantId == null || TenantContext.TECHNICAL_TENANT_ID.equals(tenantId)) {
        // Super-admins / technical contexts must impersonate a real tenant (via X-Tenant-Id)
        // before creating or listing invite links — otherwise we have nothing to scope to.
        return null;
      }
      return tenantId;
    } catch (Exception ex) {
      return null;
    }
  }

  private String generateToken() {
    byte[] buf = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(buf);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
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
    private Long topicId;
    private String linkKind;
    private String chatType;
    private String anonymity;
    private String consultantId;
    private String notes;
    private Long expiresInDays;

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
}
