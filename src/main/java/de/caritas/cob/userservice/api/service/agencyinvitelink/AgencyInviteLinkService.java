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
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.servlet.http.HttpServletRequest;
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
 * Manages one-time invite links. Each link is bound to a topic (not an agency); when redeemed it
 * creates an anonymous enquiry session via {@link CreateAnonymousEnquiryFacade}. Agency is no
 * longer carried on the link — it gets attached to the resulting session later, when a counsellor
 * accepts the enquiry.
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
  private final @NonNull CreateAnonymousEnquiryFacade createAnonymousEnquiryFacade;

  /** Create a new invite link. All classification fields are optional — defaults are applied. */
  public AgencyInviteLink create(CreateInviteLinkCommand cmd) {
    if (cmd == null) {
      throw new BadRequestException("Request body is required");
    }
    applyDefaults(cmd);
    validateEnums(cmd);

    if (InviteLinkKind.COUNSELLOR.name().equals(cmd.getLinkKind()) && isBlank(cmd.getConsultantId())) {
      throw new BadRequestException("consultantId is required when linkKind = COUNSELLOR");
    }

    Long callerTenantId = resolveCallerTenantId();
    if (callerTenantId == null) {
      throw new ForbiddenException("No tenant context — caller must be tenant-scoped");
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

    LocalDateTime now = LocalDateTime.now();
    AgencyInviteLink link =
        AgencyInviteLink.builder()
            .token(generateToken())
            .tenantId(callerTenantId)
            .agencyId(cmd.getAgencyId())
            .topicId(cmd.getTopicId())
            .linkKind(cmd.getLinkKind())
            .chatType(cmd.getChatType())
            .anonymity(cmd.getAnonymity())
            .notes(cmd.getNotes())
            .consultantId(InviteLinkKind.COUNSELLOR.name().equals(cmd.getLinkKind()) ? cmd.getConsultantId() : null)
            .createdByUserId(authenticatedUser.getUserId())
            .createdByUsername(authenticatedUser.getUsername())
            .createDate(now)
            .expiresAt(cmd.getExpiresInDays() != null && cmd.getExpiresInDays() > 0
                ? now.plusDays(cmd.getExpiresInDays())
                : null)
            .status(InviteLinkStatus.ACTIVE.name())
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

    if (linkKind != null && !isValidEnumValue(linkKind, InviteLinkKind.class)) {
      throw new BadRequestException("Unknown linkKind: " + linkKind);
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

    Page<AgencyInviteLink> result =
        repository.findAllByTenantIdAndFilters(
            callerTenantId, linkKind, topicId, chatType, status, pageable);

    // Only auto-expire when no status filter was requested, to avoid returning EXPIRED
    // rows to a caller who explicitly asked for ACTIVE.
    if (status == null) {
      result.getContent().forEach(this::autoExpireIfNeeded);
    }
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
      throw new BadRequestException("Invite link is not active");
    }

    if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(LocalDateTime.now())) {
      link.setStatus(InviteLinkStatus.EXPIRED.name());
      repository.save(link);
      throw new BadRequestException("Invite link expired");
    }

    Long previousTenant = TenantContext.getCurrentTenant();
    try {
      TenantContext.setCurrentTenant(link.getTenantId());

      Integer consultingTypeId = pickConsultingTypeId(link);

      CreateAnonymousEnquiryDTO dto = new CreateAnonymousEnquiryDTO();
      dto.setConsultingType(consultingTypeId);
      dto.setMainTopicId(link.getTopicId());
      if (InviteLinkKind.COUNSELLOR.name().equals(link.getLinkKind())) {
        dto.setConsultantId(link.getConsultantId());
      }

      CreateAnonymousEnquiryResponseDTO response =
          createAnonymousEnquiryFacade.createAnonymousEnquiry(dto);

      link.setStatus(InviteLinkStatus.USED.name());
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
    if (!isValidEnumValue(cmd.getChatType(), InviteLinkChatType.class)) {
      throw new BadRequestException(
          "chatType must be one of " + Arrays.toString(InviteLinkChatType.values()));
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

  private Long resolveCallerTenantId() {
    try {
      Long tenantId = TenantContext.getCurrentTenant();
      if (tenantId == null || TenantContext.TECHNICAL_TENANT_ID.equals(tenantId)) {
        return resolveTenantFromHeader();
      }
      return tenantId;
    } catch (Exception ex) {
      return null;
    }
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

    public Long getAgencyId() { return agencyId; }
    public void setAgencyId(Long agencyId) { this.agencyId = agencyId; }

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
