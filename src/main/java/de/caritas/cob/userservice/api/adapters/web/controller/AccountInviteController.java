package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountAccessGateStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.InviteSendResult;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.SendInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailDeliveryStatus;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateService;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateService.TemplateCommand;
import java.time.LocalDateTime;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AccountInviteController {

  private static final String ADMIN_AUTH =
      "hasAnyAuthority('AUTHORIZATION_TENANT_ADMIN', 'AUTHORIZATION_USER_ADMIN',"
          + " 'AUTHORIZATION_RESTRICTED_AGENCY_ADMIN')";

  private final @NonNull AccountInviteService accountInviteService;
  private final @NonNull InviteEmailTemplateService templateService;
  private final @NonNull InviteEmailDeliveryRepository deliveryRepository;

  @PreAuthorize(ADMIN_AUTH)
  @PostMapping("/useradmin/account-invites")
  public ResponseEntity<AccountInviteResponseDTO> createInvite(
      @RequestBody(required = false) CreateAccountInviteRequestDTO request) {
    CreateAccountInviteRequestDTO safe =
        request == null ? new CreateAccountInviteRequestDTO() : request;
    AccountInvite invite =
        accountInviteService.createInvite(
            new CreateAccountInviteCommand(
                parseEnum(AccountInviteTargetRole.class, safe.targetRole, "targetRole"),
                safe.tenantId,
                safe.recipientEmail,
                safe.firstName,
                safe.lastName,
                safe.agencyId,
                safe.departmentId,
                safe.expiresInDays));

    if (safe.templateId != null) {
      InviteSendResult result =
          accountInviteService.sendInvite(
              new SendInviteCommand(invite.getId(), safe.templateId, safe.acceptBaseUrl));
      return new ResponseEntity<>(AccountInviteResponseDTO.from(result), HttpStatus.CREATED);
    }

    return new ResponseEntity<>(
        AccountInviteResponseDTO.from(
            invite, null, accountInviteService.calculateAccessGate(invite)),
        HttpStatus.CREATED);
  }

  @PreAuthorize(ADMIN_AUTH)
  @GetMapping("/useradmin/account-invites")
  public ResponseEntity<PagedAccountInviteResponseDTO> listInvites(
      @RequestParam(value = "target_role", required = false) String targetRole,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "tenant_id", required = false) Long tenantId,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "size", required = false) Integer size) {
    Page<AccountInvite> result =
        accountInviteService.listInvites(
            parseOptionalEnum(AccountInviteTargetRole.class, targetRole, "target_role"),
            parseOptionalEnum(AccountInviteStatus.class, status, "status"),
            tenantId,
            page == null ? 0 : page,
            size == null ? 20 : size);
    List<AccountInviteResponseDTO> content =
        result.getContent().stream()
            .map(
                invite ->
                    AccountInviteResponseDTO.from(
                        invite,
                        latestDeliveryStatus(invite),
                        accountInviteService.calculateAccessGate(invite)))
            .toList();
    PagedAccountInviteResponseDTO response = new PagedAccountInviteResponseDTO();
    response.content = content;
    response.totalElements = result.getTotalElements();
    response.totalPages = result.getTotalPages();
    response.page = result.getNumber();
    response.size = result.getSize();
    return ResponseEntity.ok(response);
  }

  @PreAuthorize(ADMIN_AUTH)
  @PostMapping("/useradmin/account-invites/{inviteId}/send")
  public ResponseEntity<AccountInviteResponseDTO> sendInvite(
      @PathVariable Long inviteId, @RequestBody SendInviteRequestDTO request) {
    SendInviteRequestDTO safe = request == null ? new SendInviteRequestDTO() : request;
    InviteSendResult result =
        accountInviteService.sendInvite(
            new SendInviteCommand(inviteId, safe.templateId, safe.acceptBaseUrl));
    return ResponseEntity.ok(AccountInviteResponseDTO.from(result));
  }

  @PreAuthorize(ADMIN_AUTH)
  @PostMapping("/useradmin/account-invites/{inviteId}/resend")
  public ResponseEntity<AccountInviteResponseDTO> resendInvite(
      @PathVariable Long inviteId, @RequestBody SendInviteRequestDTO request) {
    SendInviteRequestDTO safe = request == null ? new SendInviteRequestDTO() : request;
    InviteSendResult result =
        accountInviteService.resendInvite(
            new SendInviteCommand(inviteId, safe.templateId, safe.acceptBaseUrl));
    return ResponseEntity.ok(AccountInviteResponseDTO.from(result));
  }

  @PreAuthorize(ADMIN_AUTH)
  @PostMapping("/useradmin/account-invites/{inviteId}/revoke")
  public ResponseEntity<AccountInviteResponseDTO> revokeInvite(@PathVariable Long inviteId) {
    AccountInvite invite = accountInviteService.revokeInvite(inviteId);
    return ResponseEntity.ok(
        AccountInviteResponseDTO.from(
            invite,
            latestDeliveryStatus(invite),
            accountInviteService.calculateAccessGate(invite)));
  }

  @PostMapping("/users/account-invites/{token}/accept")
  public ResponseEntity<AccountInviteResponseDTO> acceptInvite(
      @PathVariable String token, @RequestBody(required = false) AcceptInviteRequestDTO request) {
    String acceptedByUserId = request == null ? null : request.acceptedByUserId;
    AccountInvite invite = accountInviteService.acceptInvite(token, acceptedByUserId);
    return ResponseEntity.ok(
        AccountInviteResponseDTO.from(
            invite,
            latestDeliveryStatus(invite),
            accountInviteService.calculateAccessGate(invite)));
  }

  @PreAuthorize(ADMIN_AUTH)
  @PostMapping("/useradmin/invite-email-templates")
  public ResponseEntity<InviteEmailTemplateResponseDTO> createTemplate(
      @RequestBody TemplateRequestDTO request) {
    InviteEmailTemplate template = templateService.createTemplate(toCommand(request));
    return new ResponseEntity<>(InviteEmailTemplateResponseDTO.from(template), HttpStatus.CREATED);
  }

  @PreAuthorize(ADMIN_AUTH)
  @PutMapping("/useradmin/invite-email-templates/{templateId}")
  public ResponseEntity<InviteEmailTemplateResponseDTO> updateTemplate(
      @PathVariable Long templateId, @RequestBody TemplateRequestDTO request) {
    InviteEmailTemplate template = templateService.updateTemplate(templateId, toCommand(request));
    return ResponseEntity.ok(InviteEmailTemplateResponseDTO.from(template));
  }

  @PreAuthorize(ADMIN_AUTH)
  @GetMapping("/useradmin/invite-email-templates")
  public ResponseEntity<List<InviteEmailTemplateResponseDTO>> listTemplates(
      @RequestParam(value = "kind", required = false) String kind) {
    List<InviteEmailTemplateResponseDTO> response =
        templateService
            .listTemplates(parseOptionalEnum(InviteEmailTemplateKind.class, kind, "kind"))
            .stream()
            .map(InviteEmailTemplateResponseDTO::from)
            .toList();
    return ResponseEntity.ok(response);
  }

  private InviteEmailDeliveryStatus latestDeliveryStatus(AccountInvite invite) {
    if (invite.getId() == null) {
      return null;
    }
    return deliveryRepository
        .findFirstByAccountInviteIdOrderByCreateDateDesc(invite.getId())
        .map(InviteEmailDelivery::getStatus)
        .orElse(null);
  }

  private static TemplateCommand toCommand(TemplateRequestDTO request) {
    TemplateRequestDTO safe = request == null ? new TemplateRequestDTO() : request;
    return new TemplateCommand(
        parseEnum(InviteEmailTemplateKind.class, safe.kind, "kind"),
        safe.name,
        safe.language,
        safe.subject,
        safe.body,
        safe.active);
  }

  private static <E extends Enum<E>> E parseEnum(
      Class<E> enumType, String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new BadRequestException(fieldName + " is required");
    }
    return parseOptionalEnum(enumType, value, fieldName);
  }

  private static <E extends Enum<E>> E parseOptionalEnum(
      Class<E> enumType, String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    try {
      return Enum.valueOf(enumType, value.trim());
    } catch (IllegalArgumentException exception) {
      throw new BadRequestException("Unknown " + fieldName + ": " + value, exception);
    }
  }

  public static class CreateAccountInviteRequestDTO {
    public String targetRole;
    public Long tenantId;
    public String recipientEmail;
    public String firstName;
    public String lastName;
    public Long agencyId;
    public Long departmentId;
    public Long expiresInDays;
    public Long templateId;
    public String acceptBaseUrl;
  }

  public static class SendInviteRequestDTO {
    public Long templateId;
    public String acceptBaseUrl;
  }

  public static class AcceptInviteRequestDTO {
    public String acceptedByUserId;
  }

  public static class TemplateRequestDTO {
    public String kind;
    public String name;
    public String language;
    public String subject;
    public String body;
    public Boolean active;
  }

  public static class AccountInviteResponseDTO {
    public Long id;
    public String targetRole;
    public Long tenantId;
    public String recipientEmail;
    public String firstName;
    public String lastName;
    public Long agencyId;
    public Long departmentId;
    public String provisioningStatus;
    public String inviteStatus;
    public String emailVerificationStatus;
    public String emailDeliveryStatus;
    public String twoFactorStatus;
    public String accessGateStatus;
    public LocalDateTime expiresAt;
    public LocalDateTime acceptedAt;
    public LocalDateTime revokedAt;
    public LocalDateTime supersededAt;
    public String twoFactorWaivedBy;
    public LocalDateTime twoFactorWaivedAt;
    public String twoFactorWaiverReason;
    public LocalDateTime createDate;
    public String rawToken;
    public String acceptUrl;

    static AccountInviteResponseDTO from(InviteSendResult result) {
      AccountInviteResponseDTO dto =
          from(
              result.invite(),
              result.delivery().getStatus(),
              result.invite() == null ? null : AccountAccessGateStatus.BLOCKED_INVITE);
      dto.rawToken = result.rawToken();
      dto.acceptUrl = result.acceptUrl();
      return dto;
    }

    static AccountInviteResponseDTO from(
        AccountInvite invite,
        InviteEmailDeliveryStatus deliveryStatus,
        AccountAccessGateStatus accessGateStatus) {
      AccountInviteResponseDTO dto = new AccountInviteResponseDTO();
      dto.id = invite.getId();
      dto.targetRole = invite.getTargetRole() == null ? null : invite.getTargetRole().name();
      dto.tenantId = invite.getTenantId();
      dto.recipientEmail = invite.getRecipientEmail();
      dto.firstName = invite.getFirstName();
      dto.lastName = invite.getLastName();
      dto.agencyId = invite.getAgencyId();
      dto.departmentId = invite.getDepartmentId();
      dto.provisioningStatus = null;
      dto.inviteStatus = invite.getStatus() == null ? null : invite.getStatus().name();
      dto.emailVerificationStatus =
          invite.getEmailVerificationStatus() == null
              ? null
              : invite.getEmailVerificationStatus().name();
      dto.emailDeliveryStatus = deliveryStatus == null ? null : deliveryStatus.name();
      dto.twoFactorStatus =
          invite.getTwoFactorStatus() == null ? null : invite.getTwoFactorStatus().name();
      dto.accessGateStatus = accessGateStatus == null ? null : accessGateStatus.name();
      dto.expiresAt = invite.getExpiresAt();
      dto.acceptedAt = invite.getAcceptedAt();
      dto.revokedAt = invite.getRevokedAt();
      dto.supersededAt = invite.getSupersededAt();
      dto.twoFactorWaivedBy = invite.getTwoFactorWaivedBy();
      dto.twoFactorWaivedAt = invite.getTwoFactorWaivedAt();
      dto.twoFactorWaiverReason = invite.getTwoFactorWaiverReason();
      dto.createDate = invite.getCreateDate();
      return dto;
    }
  }

  public static class PagedAccountInviteResponseDTO {
    public List<AccountInviteResponseDTO> content;
    public long totalElements;
    public int totalPages;
    public int page;
    public int size;
  }

  public static class InviteEmailTemplateResponseDTO {
    public Long id;
    public String kind;
    public String name;
    public String language;
    public String subject;
    public String body;
    public Boolean active;
    public LocalDateTime createDate;
    public LocalDateTime updateDate;

    static InviteEmailTemplateResponseDTO from(InviteEmailTemplate template) {
      InviteEmailTemplateResponseDTO dto = new InviteEmailTemplateResponseDTO();
      dto.id = template.getId();
      dto.kind = template.getKind() == null ? null : template.getKind().name();
      dto.name = template.getName();
      dto.language = template.getLanguage();
      dto.subject = template.getSubject();
      dto.body = template.getBody();
      dto.active = template.getActive();
      dto.createDate = template.getCreateDate();
      dto.updateDate = template.getUpdateDate();
      return dto;
    }
  }
}
