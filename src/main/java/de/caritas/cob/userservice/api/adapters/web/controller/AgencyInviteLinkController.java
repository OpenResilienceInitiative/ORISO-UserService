package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateAnonymousEnquiryResponseDTO;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.service.agencyinvitelink.AgencyInviteLinkService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.AgencyInviteLinkService.CreateInviteLinkCommand;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AgencyInviteLinkController {

  private final @NonNull AgencyInviteLinkService agencyInviteLinkService;
  private final @NonNull TopicService topicService;

  // ---------------------------------------------------------------------------------------------
  // POST /useradmin/invitelinks — create a new topic-based invite link.
  // ---------------------------------------------------------------------------------------------
  @PreAuthorize(
      "hasAnyAuthority('AUTHORIZATION_TENANT_ADMIN', 'AUTHORIZATION_USER_ADMIN',"
          + " 'AUTHORIZATION_RESTRICTED_AGENCY_ADMIN')")
  @PostMapping("/useradmin/invitelinks")
  public ResponseEntity<AgencyInviteLinkResponseDTO> create(
      @RequestBody(required = false) CreateInviteLinkRequestDTO request) {
    CreateInviteLinkCommand cmd = new CreateInviteLinkCommand();
    if (request != null) {
      cmd.setAgencyId(request.getAgencyId());
      cmd.setTopicId(request.getTopicId());
      cmd.setLinkKind(request.getLinkKind());
      cmd.setChatType(request.getChatType());
      cmd.setAnonymity(request.getAnonymity());
      cmd.setConsultantId(request.getConsultantId());
      cmd.setNotes(request.getNotes());
      cmd.setExpiresInDays(request.getExpiresInDays());
    }
    AgencyInviteLink link = agencyInviteLinkService.create(cmd);
    Map<Long, TopicDTO> topicsMap = topicService.getAllTopicsMap();
    return new ResponseEntity<>(
        AgencyInviteLinkResponseDTO.from(link, topicsMap), HttpStatus.CREATED);
  }

  // ---------------------------------------------------------------------------------------------
  // GET /useradmin/invitelinks — list for the caller's tenant.
  //
  // Dual-format response for backward compatibility:
  //   No page/size params  → flat JSON array   (old frontend)
  //   page or size present → paginated object  (new frontend)
  // ---------------------------------------------------------------------------------------------
  @PreAuthorize(
      "hasAnyAuthority('AUTHORIZATION_TENANT_ADMIN', 'AUTHORIZATION_USER_ADMIN',"
          + " 'AUTHORIZATION_RESTRICTED_AGENCY_ADMIN')")
  @GetMapping("/useradmin/invitelinks")
  public ResponseEntity<?> list(
      @RequestParam(value = "linkKind", required = false) String linkKind,
      @RequestParam(value = "topicId", required = false) Long topicId,
      @RequestParam(value = "chatType", required = false) String chatType,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "size", required = false) Integer size) {

    int pageNum = page != null ? page : 0;
    int sizeNum = size != null ? size : 20;

    Page<AgencyInviteLink> result =
        agencyInviteLinkService.list(linkKind, topicId, chatType, status, pageNum, sizeNum);
    Map<Long, TopicDTO> topicsMap = topicService.getAllTopicsMap();
    List<AgencyInviteLinkResponseDTO> content =
        result.getContent().stream()
            .map(link -> AgencyInviteLinkResponseDTO.from(link, topicsMap))
            .collect(Collectors.toList());

    // Old frontend: no pagination params → return plain array (same as the original API)
    if (page == null && size == null) {
      return ResponseEntity.ok(content);
    }

    // New frontend: pagination params present → return paginated wrapper
    PagedInviteLinksResponseDTO body = new PagedInviteLinksResponseDTO();
    body.content = content;
    body.totalElements = result.getTotalElements();
    body.totalPages = result.getTotalPages();
    body.page = result.getNumber();
    body.size = result.getSize();
    return ResponseEntity.ok(body);
  }

  // ---------------------------------------------------------------------------------------------
  // POST /users/invitelinks/{token}/redeem — public; marks link used and returns registration
  // metadata.
  // ---------------------------------------------------------------------------------------------
  @PostMapping("/users/invitelinks/{token}/redeem")
  public ResponseEntity<RedeemResponseDTO> redeem(@PathVariable String token) {
    AgencyInviteLinkService.RedeemContext ctx = agencyInviteLinkService.redeemWithContext(token);
    CreateAnonymousEnquiryResponseDTO session = ctx.getSession();

    RedeemResponseDTO response = new RedeemResponseDTO();
    if (session != null) {
      response.userName = session.getUserName();
      response.sessionId = session.getSessionId();
      response.accessToken = session.getAccessToken();
      response.expiresIn = session.getExpiresIn();
      response.refreshToken = session.getRefreshToken();
      response.refreshExpiresIn = session.getRefreshExpiresIn();
      response.rcUserId = session.getRcUserId();
      response.rcToken = session.getRcToken();
      response.rcGroupId = session.getRcGroupId();
    }
    response.tenantId = ctx.getTenantId();
    response.agencyId = ctx.getAgencyId();
    response.consultingTypeId = ctx.getConsultingTypeId();
    response.topicId = ctx.getTopicId();

    return ResponseEntity.ok(response);
  }

  // ---------------------------------------------------------------------------------------------
  // DTOs
  // ---------------------------------------------------------------------------------------------

  public static class CreateInviteLinkRequestDTO {
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

  public static class AgencyInviteLinkResponseDTO {
    private Long id;
    private String token;
    private Long tenantId;
    private Long agencyId;
    private Long topicId;
    private String topicName;
    private String linkKind;
    private String chatType;
    private String anonymity;
    private String consultantId;
    private String notes;
    private String createdByUserId;
    private String createdByUsername;
    private LocalDateTime createDate;
    private LocalDateTime expiresAt;
    private LocalDateTime usedAt;
    private Long usedBySessionId;
    private String status;

    public static AgencyInviteLinkResponseDTO from(
        AgencyInviteLink link, Map<Long, TopicDTO> topicsMap) {
      AgencyInviteLinkResponseDTO dto = new AgencyInviteLinkResponseDTO();
      dto.id = link.getId();
      dto.token = link.getToken();
      dto.tenantId = link.getTenantId();
      dto.agencyId = link.getAgencyId();
      dto.topicId = link.getTopicId();
      dto.topicName =
          link.getTopicId() != null && topicsMap.containsKey(link.getTopicId())
              ? topicsMap.get(link.getTopicId()).getName()
              : null;
      dto.linkKind = link.getLinkKind();
      dto.chatType = link.getChatType();
      dto.anonymity = link.getAnonymity();
      dto.consultantId = link.getConsultantId();
      dto.notes = link.getNotes();
      dto.createdByUserId = link.getCreatedByUserId();
      dto.createdByUsername = link.getCreatedByUsername();
      dto.createDate = link.getCreateDate();
      dto.expiresAt = link.getExpiresAt();
      dto.usedAt = link.getUsedAt();
      dto.usedBySessionId = link.getUsedBySessionId();
      dto.status = link.getStatus();
      return dto;
    }

    public Long getId() {
      return id;
    }

    public String getToken() {
      return token;
    }

    public Long getTenantId() {
      return tenantId;
    }

    public Long getAgencyId() {
      return agencyId;
    }

    public Long getTopicId() {
      return topicId;
    }

    public String getTopicName() {
      return topicName;
    }

    public String getLinkKind() {
      return linkKind;
    }

    public String getChatType() {
      return chatType;
    }

    public String getAnonymity() {
      return anonymity;
    }

    public String getConsultantId() {
      return consultantId;
    }

    public String getNotes() {
      return notes;
    }

    public String getCreatedByUserId() {
      return createdByUserId;
    }

    public String getCreatedByUsername() {
      return createdByUsername;
    }

    public LocalDateTime getCreateDate() {
      return createDate;
    }

    public LocalDateTime getExpiresAt() {
      return expiresAt;
    }

    public LocalDateTime getUsedAt() {
      return usedAt;
    }

    public Long getUsedBySessionId() {
      return usedBySessionId;
    }

    public String getStatus() {
      return status;
    }
  }

  public static class PagedInviteLinksResponseDTO {
    public List<AgencyInviteLinkResponseDTO> content;
    public long totalElements;
    public int totalPages;
    public int page;
    public int size;

    public List<AgencyInviteLinkResponseDTO> getContent() {
      return content;
    }

    public long getTotalElements() {
      return totalElements;
    }

    public int getTotalPages() {
      return totalPages;
    }

    public int getPage() {
      return page;
    }

    public int getSize() {
      return size;
    }
  }

  /**
   * Redeem response — registration metadata; optional session credentials when server creates
   * session.
   */
  public static class RedeemResponseDTO {
    // New session credentials (new frontend uses these)
    public String userName;
    public Long sessionId;
    public String accessToken;
    public Integer expiresIn;
    public String refreshToken;
    public Integer refreshExpiresIn;
    public String rcUserId;
    public String rcToken;
    public String rcGroupId;
    public Long tenantId;
    public Long agencyId;
    public Integer consultingTypeId;
    public Long topicId;

    public String getUserName() {
      return userName;
    }

    public Long getSessionId() {
      return sessionId;
    }

    public String getAccessToken() {
      return accessToken;
    }

    public Integer getExpiresIn() {
      return expiresIn;
    }

    public String getRefreshToken() {
      return refreshToken;
    }

    public Integer getRefreshExpiresIn() {
      return refreshExpiresIn;
    }

    public String getRcUserId() {
      return rcUserId;
    }

    public String getRcToken() {
      return rcToken;
    }

    public String getRcGroupId() {
      return rcGroupId;
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
