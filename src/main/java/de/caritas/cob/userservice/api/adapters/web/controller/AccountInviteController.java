package de.caritas.cob.userservice.api.adapters.web.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.model.TopicPermission;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountAccessGateStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.InviteSendResult;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.SendInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.WaiveTwoFactorCommand;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTopicPermissionService;
import de.caritas.cob.userservice.api.service.accountinvite.CounsellorInviteProvisioningService;
import de.caritas.cob.userservice.api.service.accountinvite.CounsellorInviteProvisioningService.ProvisionCounsellorCommand;
import de.caritas.cob.userservice.api.service.accountinvite.InviteAccountRoles;
import de.caritas.cob.userservice.api.service.accountinvite.InviteBoard;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailDeliveryStatus;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailPreviewService;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailPreviewService.InviteEmailPreview;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailPreviewService.PreviewCommand;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateService;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateService.TemplateCommand;
import de.caritas.cob.userservice.api.service.accountinvite.InviteProgress;
import de.caritas.cob.userservice.api.service.accountinvite.InviteQueueProblem;
import de.caritas.cob.userservice.api.service.accountinvite.InviteRoleChange;
import de.caritas.cob.userservice.api.service.accountinvite.InviteRoleChange.ChangeRoleCommand;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import de.caritas.cob.userservice.api.service.accountinvite.UnitQueue;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  private final @NonNull CounsellorInviteProvisioningService counsellorInviteProvisioningService;
  private final @NonNull InviteEmailTemplateService templateService;
  private final @NonNull InviteEmailDeliveryRepository deliveryRepository;
  private final @NonNull InviteEmailPreviewService previewService;
  private final @NonNull AccountInviteTopicPermissionService topicPermissionService;
  private final @NonNull UnitQueue unitQueue;
  private final @NonNull InviteBoard inviteBoard;
  private final @NonNull InviteAccountRoles inviteAccountRoles;
  private final @NonNull InviteRoleChange roleChange;

  @PreAuthorize(ADMIN_AUTH)
  @PostMapping("/useradmin/account-invites")
  public ResponseEntity<AccountInviteResponseDTO> createInvite(
      @RequestBody(required = false) CreateAccountInviteRequestDTO request) {
    CreateAccountInviteRequestDTO safe =
        request == null ? new CreateAccountInviteRequestDTO() : request;
    CreateAccountInviteCommand command =
        new CreateAccountInviteCommand(
            parseEnum(AccountInviteTargetRole.class, safe.targetRole, "targetRole"),
            safe.tenantId,
            safe.recipientEmail,
            safe.firstName,
            safe.lastName,
            safe.agencyId,
            safe.departmentId,
            safe.expiresInDays,
            parseOptionalEnum(
                IdAllocationMode.class, safe.tenantIdAllocationMode, "tenantIdAllocationMode"),
            parseOptionalEnum(
                IdAllocationMode.class, safe.agencyIdAllocationMode, "agencyIdAllocationMode"),
            safe.alsoCounsellor,
            TopicPermission.fromWire(safe.topicPermission));

    if (safe.templateId != null) {
      InviteSendResult result = accountInviteService.createAndSendInvite(command, safe.templateId);
      return new ResponseEntity<>(
          withDerivedState(AccountInviteResponseDTO.from(result), result.invite()),
          HttpStatus.CREATED);
    }

    AccountInvite invite = accountInviteService.createInvite(command);

    return new ResponseEntity<>(
        withDerivedState(
            AccountInviteResponseDTO.from(
                invite, null, accountInviteService.calculateAccessGate(invite)),
            invite),
        HttpStatus.CREATED);
  }

  @PreAuthorize(ADMIN_AUTH)
  @GetMapping("/useradmin/account-invites")
  public ResponseEntity<PagedAccountInviteResponseDTO> listInvites(
      @RequestParam(value = "tab", required = false) String tab,
      @RequestParam(value = "target_role", required = false) String targetRole,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "progress_phase", required = false) String progressPhase,
      @RequestParam(value = "tenant_id", required = false) Long tenantId,
      @RequestParam(value = "query", required = false) String query,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "size", required = false) Integer size) {
    InviteBoard.Listing listing =
        inviteBoard.list(
            parseOptionalEnum(InviteBoard.Tab.class, tab, "tab"),
            parseOptionalEnum(AccountInviteTargetRole.class, targetRole, "target_role"),
            parseOptionalEnum(AccountInviteStatus.class, status, "status"),
            parseOptionalEnum(InviteProgress.Phase.class, progressPhase, "progress_phase"),
            tenantId,
            query,
            page == null ? 0 : page,
            size == null ? 20 : size);
    Page<InviteBoard.Row> result = listing.rows();
    List<AccountInvite> invites =
        result.getContent().stream().map(InviteBoard.Row::invite).toList();
    Map<Long, TopicPermission> permissions = topicPermissionService.currentPermissions(invites);
    Map<Long, List<AccountInviteTargetRole>> accountRoles = inviteAccountRoles.of(invites);
    List<AccountInviteResponseDTO> content =
        result.getContent().stream()
            .map(
                row ->
                    withDerivedState(
                        AccountInviteResponseDTO.from(
                            row.invite(),
                            row.latestDelivery() == null ? null : row.latestDelivery().getStatus(),
                            accountInviteService.calculateAccessGate(row.invite())),
                        row,
                        permissions,
                        accountRoles))
            .toList();
    PagedAccountInviteResponseDTO response = new PagedAccountInviteResponseDTO();
    response.content = content;
    response.totalElements = result.getTotalElements();
    response.totalPages = result.getTotalPages();
    response.page = result.getNumber();
    response.size = result.getSize();
    response.phaseCounts = new LinkedHashMap<>();
    response.phaseDetailCounts = new LinkedHashMap<>();
    listing
        .tally()
        .counts()
        .forEach((phase, count) -> response.phaseCounts.put(phase.name(), count));
    listing
        .tally()
        .details()
        .forEach((phase, details) -> response.phaseDetailCounts.put(phase.name(), details));
    return ResponseEntity.ok(response);
  }

  /**
   * Before acceptance only; COUNSELLOR and AGENCY_ADMIN swap, a Träger-level role needs a new
   * invite. An existing account gains a role through {@code POST
   * /useradmin/consultants/{id}/roles}.
   */
  @PreAuthorize(ADMIN_AUTH)
  @PutMapping("/useradmin/account-invites/{inviteId}/role")
  public ResponseEntity<AccountInviteResponseDTO> changeRole(
      @PathVariable Long inviteId, @RequestBody(required = false) ChangeRoleRequestDTO request) {
    ChangeRoleRequestDTO safe = request == null ? new ChangeRoleRequestDTO() : request;
    AccountInvite invite =
        roleChange.change(
            inviteId,
            new ChangeRoleCommand(
                parseEnum(AccountInviteTargetRole.class, safe.targetRole, "targetRole"),
                safe.alsoCounsellor));
    return ResponseEntity.ok(
        withDerivedState(
            AccountInviteResponseDTO.from(
                invite,
                latestDeliveryStatus(invite),
                accountInviteService.calculateAccessGate(invite)),
            invite));
  }

  @PreAuthorize(ADMIN_AUTH)
  @PostMapping("/useradmin/account-invites/{inviteId}/send")
  public ResponseEntity<AccountInviteResponseDTO> sendInvite(
      @PathVariable Long inviteId, @RequestBody SendInviteRequestDTO request) {
    SendInviteRequestDTO safe = request == null ? new SendInviteRequestDTO() : request;
    InviteSendResult result =
        accountInviteService.sendInvite(new SendInviteCommand(inviteId, safe.templateId));
    return ResponseEntity.ok(AccountInviteResponseDTO.from(result));
  }

  @PreAuthorize(ADMIN_AUTH)
  @PostMapping("/useradmin/account-invites/{inviteId}/resend")
  public ResponseEntity<AccountInviteResponseDTO> resendInvite(
      @PathVariable Long inviteId, @RequestBody SendInviteRequestDTO request) {
    SendInviteRequestDTO safe = request == null ? new SendInviteRequestDTO() : request;
    InviteSendResult result =
        accountInviteService.resendInvite(new SendInviteCommand(inviteId, safe.templateId));
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

  /** Also after the account exists; the counsellor's own permission follows. */
  @PreAuthorize(ADMIN_AUTH)
  @PutMapping("/useradmin/account-invites/{inviteId}/topic-permission")
  public ResponseEntity<AccountInviteResponseDTO> updateTopicPermission(
      @PathVariable Long inviteId,
      @RequestBody(required = false) TopicPermissionRequestDTO request) {
    TopicPermission permission =
        TopicPermission.fromWire(request == null ? null : request.topicPermission);
    AccountInvite invite = topicPermissionService.updatePermission(inviteId, permission);
    return ResponseEntity.ok(
        withDerivedState(
            AccountInviteResponseDTO.from(
                invite,
                latestDeliveryStatus(invite),
                accountInviteService.calculateAccessGate(invite)),
            invite));
  }

  @PreAuthorize(ADMIN_AUTH)
  @PostMapping("/useradmin/account-invites/{inviteId}/waive-two-factor")
  public ResponseEntity<AccountInviteResponseDTO> waiveTwoFactor(
      @PathVariable Long inviteId,
      @RequestBody(required = false) WaiveTwoFactorRequestDTO request) {
    String reason = request == null ? null : request.reason;
    AccountInvite invite =
        accountInviteService.waiveTwoFactor(inviteId, new WaiveTwoFactorCommand(reason));
    return ResponseEntity.ok(
        AccountInviteResponseDTO.from(
            invite,
            latestDeliveryStatus(invite),
            accountInviteService.calculateAccessGate(invite)));
  }

  /**
   * Public accept endpoint. Resume contract (ORISO-Admin#569 hardening): while the accepted
   * invite's mandatory 2FA activation is pending and the link is unexpired, repeated calls stay
   * idempotent 200s carrying {@code phase = PENDING_2FA_ACTIVATION}; once the gate is satisfied the
   * link is terminally consumed (410 {@code reason=CONSUMED}).
   */
  @PostMapping({
    "/users/account-invites/{token}/accept",
    "/service/users/account-invites/{token}/accept"
  })
  public ResponseEntity<AccountInviteResponseDTO> acceptInvite(
      @PathVariable String token, @RequestBody(required = false) AcceptInviteRequestDTO request) {
    AccountInvite invite =
        counsellorInviteProvisioningService.acceptInvite(
            token,
            request == null
                ? null
                : new ProvisionCounsellorCommand(
                    request.username,
                    request.password,
                    request.formalLanguage,
                    request.acceptedByUserId));
    AccountInviteResponseDTO response =
        AccountInviteResponseDTO.fromPublic(
            invite, latestDeliveryStatus(invite), accountInviteService.calculateAccessGate(invite));
    response.phase =
        invite.getTwoFactorStatus() == TwoFactorGateStatus.PENDING_SETUP
            ? AcceptPhase.PENDING_2FA_ACTIVATION.name()
            : AcceptPhase.COMPLETED.name();
    return ResponseEntity.ok(response);
  }

  @GetMapping("/users/account-invites/{token}")
  public ResponseEntity<AccountInviteResponseDTO> getInvite(@PathVariable String token) {
    AccountInvite invite = accountInviteService.requireActiveInvite(token);
    return ResponseEntity.ok(
        AccountInviteResponseDTO.fromPublic(
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

  /**
   * Renders the canonical branded mail with the current branding (ORISO-UserService#914) so the
   * Admin can show what is actually sent instead of re-implementing the markup. Same platform-admin
   * authorisation as the other {@code /useradmin/invite-email-templates} endpoints.
   *
   * <p>Use {@code templateId} to preview a stored template, {@code kind} to pick the sample content
   * for a template kind, and {@code tenant_id} to preview a specific tenant's branding. Without any
   * parameter the endpoint renders the built-in sample invite with platform branding — that is the
   * fixture the Admin Storybook stories are generated from.
   */
  @PreAuthorize(ADMIN_AUTH)
  @GetMapping("/useradmin/invite-email-templates/preview")
  public ResponseEntity<InviteEmailPreviewResponseDTO> previewTemplate(
      @RequestParam(value = "templateId", required = false) Long templateId,
      @RequestParam(value = "kind", required = false) String kind,
      @RequestParam(value = "tenant_id", required = false) Long tenantId,
      @RequestParam(value = "language", required = false) String language) {
    return ResponseEntity.ok(
        InviteEmailPreviewResponseDTO.from(
            previewService.preview(
                new PreviewCommand(
                    templateId,
                    parseOptionalEnum(InviteEmailTemplateKind.class, kind, "kind"),
                    null,
                    null,
                    tenantId,
                    language))));
  }

  /** Live preview of unsaved editor content — same renderer, same branding, same output shape. */
  @PreAuthorize(ADMIN_AUTH)
  @PostMapping("/useradmin/invite-email-templates/preview")
  public ResponseEntity<InviteEmailPreviewResponseDTO> previewTemplateContent(
      @RequestBody(required = false) PreviewRequestDTO request) {
    PreviewRequestDTO safe = request == null ? new PreviewRequestDTO() : request;
    return ResponseEntity.ok(
        InviteEmailPreviewResponseDTO.from(
            previewService.preview(
                new PreviewCommand(
                    safe.templateId,
                    parseOptionalEnum(InviteEmailTemplateKind.class, safe.kind, "kind"),
                    safe.subject,
                    safe.body,
                    safe.tenantId,
                    safe.language))));
  }

  private AccountInviteResponseDTO withDerivedState(
      AccountInviteResponseDTO dto, AccountInvite invite) {
    return withDerivedState(
        dto,
        inviteBoard.rowOf(invite),
        topicPermissionService.currentPermissions(List.of(invite)),
        inviteAccountRoles.of(List.of(invite)));
  }

  /**
   * Derived on read: queue problem, progress, and the counsellor's own permission once onboarded.
   */
  private AccountInviteResponseDTO withDerivedState(
      AccountInviteResponseDTO dto,
      InviteBoard.Row row,
      Map<Long, TopicPermission> permissions,
      Map<Long, List<AccountInviteTargetRole>> accountRoles) {
    InviteQueueProblem problem = row.queueProblem();
    dto.queueProblem = problem == null ? null : problem.name();
    InviteProgress progress = row.progress();
    dto.progressPhase = progress.phase().name();
    dto.unitCreatedAt = progress.unitCreatedAt();
    dto.sentAt = progress.sentAt();
    dto.accountCreatedAt = progress.accountCreatedAt();
    dto.twoFactorDoneAt = progress.twoFactorDoneAt();
    dto.completedAt = progress.completedAt();
    List<AccountInviteTargetRole> roles = accountRoles.get(row.invite().getId());
    dto.accountRoles = roles == null ? null : roles.stream().map(Enum::name).toList();
    TopicPermission permission = permissions.get(row.invite().getId());
    if (permission != null) {
      dto.topicPermission = permission.name();
    }
    return dto;
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

    /**
     * Ignored since TEN-INV-U6 (#890): the accept link target is decided server-side from the
     * invite's role and configuration. Kept only for wire compatibility with older clients.
     */
    public String acceptBaseUrl;

    /**
     * TEN-INV-U3: AUTO = the owning service assigns the smallest free ID (the matching ID field
     * must be omitted); MANUAL = the pinned ID is reserved or rejected with 409 (both only for
     * TENANT_ADMIN invites, i.e. a new Träger). EXISTING: {@code tenantId} names an existing
     * Träger, nothing is reserved (404 unknown, 403 out of scope, 400 for 0 or missing; a Träger
     * admin who names none gets their own).
     */
    public String tenantIdAllocationMode;

    /**
     * AUTO / MANUAL as above, or EXISTING: {@code agencyId} names an existing agency that is
     * validated, not reserved; a missing tenant or single topic is taken from the agency.
     */
    public String agencyIdAllocationMode;

    /** AGENCY_ADMIN invites only; omitted = true. Set for any other role → 400. */
    public Boolean alsoCounsellor;

    /** Object, not enum: the CSV import sends true/false. Omitted = SELECT_EXISTING. */
    public Object topicPermission;
  }

  public static class TopicPermissionRequestDTO {
    public Object topicPermission;
  }

  public static class ChangeRoleRequestDTO {
    public String targetRole;

    /** AGENCY_ADMIN only; omitted keeps it, or is true when a counsellor invite is promoted. */
    public Boolean alsoCounsellor;
  }

  public static class SendInviteRequestDTO {
    public Long templateId;

    /**
     * Ignored since TEN-INV-U6 (#890): the accept link target is decided server-side from the
     * invite's role and configuration. Kept only for wire compatibility with older clients.
     */
    public String acceptBaseUrl;
  }

  public static class WaiveTwoFactorRequestDTO {
    public String reason;
  }

  public static class AcceptInviteRequestDTO {
    public String username;
    public String password;
    public Boolean formalLanguage;
    public String acceptedByUserId;
  }

  /** Onboarding phase reported by the public accept endpoint (ORISO-Admin#569 resume contract). */
  public enum AcceptPhase {
    /** Invite consumed, but the mandatory 2FA activation is still open — the link is resumable. */
    PENDING_2FA_ACTIVATION,
    /** All account gates of the invite are satisfied. */
    COMPLETED
  }

  public static class PreviewRequestDTO {
    public Long templateId;
    public String kind;
    public String subject;
    public String body;
    public Long tenantId;
    public String language;
  }

  /** Rendered branded mail — everything the Admin needs to display or snapshot the real output. */
  public static class InviteEmailPreviewResponseDTO {
    public Long templateId;
    public String templateName;
    public String kind;
    public String language;
    public String subject;
    public String html;
    public String plainText;
    public String sampleAcceptUrl;

    static InviteEmailPreviewResponseDTO from(InviteEmailPreview preview) {
      InviteEmailPreviewResponseDTO dto = new InviteEmailPreviewResponseDTO();
      dto.templateId = preview.templateId();
      dto.templateName = preview.templateName();
      dto.kind = preview.kind() == null ? null : preview.kind().name();
      dto.language = preview.language();
      dto.subject = preview.subject();
      dto.html = preview.html();
      dto.plainText = preview.plainText();
      dto.sampleAcceptUrl = preview.sampleAcceptUrl();
      return dto;
    }
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

    /** AUTO / MANUAL (new Träger) or EXISTING; null on older invites. */
    public String tenantIdAllocationMode;

    /** AUTO / MANUAL (new Beratungsstelle) or EXISTING; null on older invites. */
    public String agencyIdAllocationMode;

    /** AGENCY_ADMIN invites: whether the person also counsels; null for every other role. */
    public Boolean alsoCounsellor;

    /** AGENCY or TENANT while WAITING_FOR_UNIT: that unit does not exist yet. */
    public String waitingForUnit;

    /** NO_UNIT_ADMIN while no pending admin invite exists for the unit; clears itself. */
    public String queueProblem;

    public String provisioningStatus;
    public String provisionedUserId;
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

    /**
     * DPA contract state for the Admin invite progress board (ORISO-Admin#896, epic #725). The
     * frontend consumes these optional fields by exactly these names: {@code dpaForwardedAt} proves
     * the "Vertragsunterlagen weitergeleitet" phase, {@code dpaSignedAt} the final "Vertrag
     * unterschrieben" phase — without it the board must not claim completion. {@code
     * dpaForwardCount} accompanies them so the board can tell a first forward from a re-forward.
     */
    public LocalDateTime dpaForwardedAt;

    public Integer dpaForwardCount;
    public LocalDateTime dpaSignedAt;

    public String topicPermission;

    /**
     * Admin tracker (#1026): PREPARED, INVITED, ACCOUNT_CREATED, DONE, NEEDS_ACTION or CLOSED,
     * derived in {@code InviteProgress}; the dates below belong to its steps, null until reached.
     */
    public String progressPhase;

    public LocalDateTime unitCreatedAt;
    public LocalDateTime sentAt;
    public LocalDateTime accountCreatedAt;
    public LocalDateTime twoFactorDoneAt;
    public LocalDateTime completedAt;

    /** Accepted invites: the roles the account holds now, also ones added later; else null. */
    public List<String> accountRoles;

    /**
     * Only set by the public accept endpoint (ORISO-Admin#569 resume contract): {@code
     * PENDING_2FA_ACTIVATION} while the mandatory 2FA activation is open (link resumable), {@code
     * COMPLETED} once every account gate is satisfied. {@code null} on admin-facing endpoints.
     */
    public String phase;

    static AccountInviteResponseDTO from(InviteSendResult result) {
      AccountInviteResponseDTO dto =
          from(
              result.invite(),
              result.delivery() == null ? null : result.delivery().getStatus(),
              result.invite() == null ? null : AccountAccessGateStatus.BLOCKED_INVITE);
      dto.rawToken = result.rawToken();
      dto.acceptUrl = result.acceptUrl();
      return dto;
    }

    /**
     * View for the public token endpoints, which answer to whoever holds the raw invite link. The
     * DPA progress state is Admin invite-board material (ORISO-Admin#896) and stays out of the
     * anonymous responses - the wizard has its own resolve contract for what the invitee needs.
     * Nulling the fields is not enough: the service configures no global NON_NULL inclusion, so a
     * null still emits the JSON key - the public shape must not carry the keys at all, hence the
     * {@code PublicAccountInviteResponseDTO} subtype whose serialization drops them.
     */
    static AccountInviteResponseDTO fromPublic(
        AccountInvite invite,
        InviteEmailDeliveryStatus deliveryStatus,
        AccountAccessGateStatus accessGateStatus) {
      PublicAccountInviteResponseDTO dto =
          fill(new PublicAccountInviteResponseDTO(), invite, deliveryStatus, accessGateStatus);
      // Kept null at the Java level too, so no code path can read admin state off a public view.
      dto.dpaForwardedAt = null;
      dto.dpaForwardCount = null;
      dto.dpaSignedAt = null;
      return dto;
    }

    static AccountInviteResponseDTO from(
        AccountInvite invite,
        InviteEmailDeliveryStatus deliveryStatus,
        AccountAccessGateStatus accessGateStatus) {
      return fill(new AccountInviteResponseDTO(), invite, deliveryStatus, accessGateStatus);
    }

    private static <T extends AccountInviteResponseDTO> T fill(
        T dto,
        AccountInvite invite,
        InviteEmailDeliveryStatus deliveryStatus,
        AccountAccessGateStatus accessGateStatus) {
      dto.id = invite.getId();
      dto.targetRole = invite.getTargetRole() == null ? null : invite.getTargetRole().name();
      dto.tenantId = invite.getTenantId();
      dto.recipientEmail = invite.getRecipientEmail();
      dto.firstName = invite.getFirstName();
      dto.lastName = invite.getLastName();
      dto.agencyId = invite.getAgencyId();
      dto.departmentId = invite.getDepartmentId();
      dto.tenantIdAllocationMode =
          invite.getTenantIdAllocationMode() == null
              ? null
              : invite.getTenantIdAllocationMode().name();
      dto.agencyIdAllocationMode =
          invite.getAgencyIdAllocationMode() == null
              ? null
              : invite.getAgencyIdAllocationMode().name();
      dto.alsoCounsellor = invite.getAlsoCounsellor();
      dto.waitingForUnit =
          invite.getWaitingForUnit() == null ? null : invite.getWaitingForUnit().name();
      dto.provisioningStatus =
          invite.getProvisioningStatus() == null ? null : invite.getProvisioningStatus().name();
      dto.provisionedUserId = invite.getProvisionedUserId();
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
      dto.dpaForwardedAt = invite.getDpaForwardedAt();
      dto.dpaForwardCount = invite.getDpaForwardCount();
      dto.dpaSignedAt = invite.getDpaSignedAt();
      dto.topicPermission =
          invite.getTopicPermission() == null ? null : invite.getTopicPermission().name();
      return dto;
    }
  }

  /**
   * Wire shape of the public token endpoints: identical to {@link AccountInviteResponseDTO} minus
   * the Admin progress-board DPA state - the keys are ABSENT, not null, so the anonymous payload
   * does not even advertise that vocabulary (ORISO-Admin#896). Admin endpoints keep the full shape
   * with {@code dpaSignedAt} present-as-null until signed.
   */
  @JsonIgnoreProperties({
    "dpaForwardedAt",
    "dpaForwardCount",
    "dpaSignedAt",
    "queueProblem",
    "progressPhase",
    "unitCreatedAt",
    "sentAt",
    "accountCreatedAt",
    "twoFactorDoneAt",
    "completedAt",
    "accountRoles"
  })
  public static class PublicAccountInviteResponseDTO extends AccountInviteResponseDTO {}

  public static class PagedAccountInviteResponseDTO {
    public List<AccountInviteResponseDTO> content;
    public long totalElements;
    public int totalPages;
    public int page;
    public int size;

    /** Every progress phase counted over all pages of the tab; status and phase filters ignored. */
    public Map<String, Long> phaseCounts;

    /** Per phase, what its count is made of: the status, or why a NEEDS_ACTION invite is stuck. */
    public Map<String, Map<String, Long>> phaseDetailCounts;
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
