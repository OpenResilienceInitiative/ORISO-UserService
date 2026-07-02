package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.ConsultingTypeService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.AgencyInviteLinkService.CreateInviteLinkCommand;
import de.caritas.cob.userservice.api.service.agencyinvitelink.AgencyInviteLinkService.RedeemContext;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgencyInviteLinkServiceTest {

  @Mock private AgencyInviteLinkRepository repository;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private TopicService topicService;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private ConsultingTypeService consultingTypeService;
  @Mock private AgencyService agencyService;

  @InjectMocks private AgencyInviteLinkService service;

  @BeforeEach
  void setTenantContext() {
    TenantContext.setCurrentTenant(1L);
  }

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  // --- create ---

  @Test
  void create_Should_ThrowBadRequest_When_CommandIsNull() {
    assertThatThrownBy(() -> service.create(null))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("required");
  }

  @Test
  void create_Should_SaveLink_When_ValidMinimalCommand() {
    CreateInviteLinkCommand cmd = new CreateInviteLinkCommand();
    when(authenticatedUser.getUserId()).thenReturn("user-1");
    when(authenticatedUser.getUsername()).thenReturn("testuser");
    AgencyInviteLink saved =
        AgencyInviteLink.builder()
            .token("token")
            .tenantId(1L)
            .status(InviteLinkStatus.ACTIVE.name())
            .build();
    when(repository.save(any())).thenReturn(saved);

    AgencyInviteLink result = service.create(cmd);

    assertThat(result).isNotNull();
    verify(repository).save(any(AgencyInviteLink.class));
  }

  @Test
  void create_Should_ThrowBadRequest_When_LinkKindIsCounsellorButNoConsultantId() {
    CreateInviteLinkCommand cmd = new CreateInviteLinkCommand();
    cmd.setLinkKind(InviteLinkKind.COUNSELLOR.name());

    assertThatThrownBy(() -> service.create(cmd))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("consultantId");
  }

  @Test
  void create_Should_ThrowBadRequest_When_InvalidLinkKind() {
    CreateInviteLinkCommand cmd = new CreateInviteLinkCommand();
    cmd.setLinkKind("INVALID_KIND");

    assertThatThrownBy(() -> service.create(cmd))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("linkKind");
  }

  @Test
  void create_Should_ThrowBadRequest_When_NotesTooLong() {
    CreateInviteLinkCommand cmd = new CreateInviteLinkCommand();
    cmd.setNotes("x".repeat(501));

    assertThatThrownBy(() -> service.create(cmd))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("notes");
  }

  @Test
  void create_Should_ThrowBadRequest_When_ExpiresInDaysIsZero() {
    CreateInviteLinkCommand cmd = new CreateInviteLinkCommand();
    cmd.setExpiresInDays(0L);

    assertThatThrownBy(() -> service.create(cmd))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("expiresInDays");
  }

  @Test
  void create_Should_ThrowBadRequest_When_ExpiresInDaysAbove365() {
    CreateInviteLinkCommand cmd = new CreateInviteLinkCommand();
    cmd.setExpiresInDays(366L);

    assertThatThrownBy(() -> service.create(cmd))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("expiresInDays");
  }

  @Test
  void create_Should_ThrowForbidden_When_AgencyIsOutsideCallerTenant() {
    CreateInviteLinkCommand cmd = new CreateInviteLinkCommand();
    cmd.setAgencyId(10L);
    AgencyDTO agency = new AgencyDTO();
    agency.setId(10L);
    agency.setTenantId(99L); // different tenant
    agency.setConsultingType(1);
    when(agencyService.getAgencyWithoutCaching(10L)).thenReturn(agency);

    assertThatThrownBy(() -> service.create(cmd))
        .isInstanceOf(ForbiddenException.class)
        .hasMessageContaining("tenant");
  }

  @Test
  void create_Should_ThrowNotFound_When_AgencyDoesNotExist() {
    CreateInviteLinkCommand cmd = new CreateInviteLinkCommand();
    cmd.setAgencyId(10L);
    when(agencyService.getAgencyWithoutCaching(10L)).thenReturn(null);

    assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void create_Should_ThrowForbidden_When_NoTenantContext() {
    TenantContext.clear();
    CreateInviteLinkCommand cmd = new CreateInviteLinkCommand();

    assertThatThrownBy(() -> service.create(cmd))
        .isInstanceOf(ForbiddenException.class)
        .hasMessageContaining("tenant");
  }

  @Test
  void create_Should_ThrowForbidden_When_ConsultantIsOutsideTenant() {
    CreateInviteLinkCommand cmd = new CreateInviteLinkCommand();
    cmd.setLinkKind(InviteLinkKind.COUNSELLOR.name());
    cmd.setConsultantId("consultant-99");
    Consultant consultant = new Consultant();
    consultant.setTenantId(99L); // wrong tenant
    when(consultantRepository.findByIdAndDeleteDateIsNull("consultant-99"))
        .thenReturn(Optional.of(consultant));

    assertThatThrownBy(() -> service.create(cmd))
        .isInstanceOf(ForbiddenException.class)
        .hasMessageContaining("tenant");
  }

  // --- redeem ---

  @Test
  void redeem_Should_ThrowNotFound_When_TokenDoesNotExist() {
    when(repository.findByTokenAndStatus("bad-token", InviteLinkStatus.ACTIVE.name()))
        .thenReturn(Optional.empty());
    when(repository.findByToken("bad-token")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.redeem("bad-token")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void redeem_Should_ThrowBadRequest_When_LinkIsAlreadyUsed() {
    AgencyInviteLink usedLink =
        AgencyInviteLink.builder()
            .token("token")
            .status(InviteLinkStatus.USED.name())
            .tenantId(1L)
            .build();
    when(repository.findByTokenAndStatus("token", InviteLinkStatus.ACTIVE.name()))
        .thenReturn(Optional.empty());
    when(repository.findByToken("token")).thenReturn(Optional.of(usedLink));

    assertThatThrownBy(() -> service.redeem("token"))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("already used");
  }

  @Test
  void redeem_Should_ThrowBadRequest_When_LinkIsExpiredByDate() {
    AgencyInviteLink expiredLink =
        AgencyInviteLink.builder()
            .token("token")
            .status(InviteLinkStatus.ACTIVE.name())
            .tenantId(1L)
            .expiresAt(LocalDateTime.now().minusHours(1))
            .build();
    when(repository.findByTokenAndStatus("token", InviteLinkStatus.ACTIVE.name()))
        .thenReturn(Optional.of(expiredLink));
    when(repository.save(any())).thenReturn(expiredLink);

    assertThatThrownBy(() -> service.redeem("token"))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("expired");
  }

  @Test
  void redeem_Should_ReturnContext_When_ValidToken() {
    AgencyInviteLink link =
        AgencyInviteLink.builder()
            .token("token")
            .status(InviteLinkStatus.ACTIVE.name())
            .tenantId(1L)
            .agencyId(5L)
            .consultingTypeId(2)
            .build();
    when(repository.findByTokenAndStatus("token", InviteLinkStatus.ACTIVE.name()))
        .thenReturn(Optional.of(link));

    RedeemContext ctx = service.redeem("token");

    assertThat(ctx).isNotNull();
    assertThat(ctx.getTenantId()).isEqualTo(1L);
    assertThat(ctx.getAgencyId()).isEqualTo(5L);
    assertThat(ctx.getConsultingTypeId()).isEqualTo(2);
  }

  // --- list ---

  @Test
  void list_Should_ThrowBadRequest_When_InvalidLinkKind() {
    assertThatThrownBy(() -> service.list("INVALID", null, null, null, 0, 20))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("linkKind");
  }

  @Test
  void list_Should_ThrowBadRequest_When_InvalidChatType() {
    assertThatThrownBy(() -> service.list(null, null, "INVALID", null, 0, 20))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("chatType");
  }

  @Test
  void list_Should_ThrowBadRequest_When_InvalidStatus() {
    assertThatThrownBy(() -> service.list(null, null, null, "INVALID", 0, 20))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("status");
  }

  // ---------------------------------------------------------------------------
  // Extended coverage — 2026-07-02
  // ---------------------------------------------------------------------------

  @Test
  void list_Should_ReturnPage_When_ValidFiltersAndTenantSet() {
    when(repository.findAllByTenantIdAndFilters(any(), any(), any(), any(), any(), any()))
        .thenReturn(org.springframework.data.domain.Page.empty());

    var result = service.list(null, null, null, "ACTIVE", 0, 20);

    assertThat(result).isNotNull();
  }

  @Test
  void list_Should_ClampSize_When_SizeIsLessThan1() {
    when(repository.findAllByTenantIdAndFilters(any(), any(), any(), any(), any(), any()))
        .thenReturn(org.springframework.data.domain.Page.empty());

    var result = service.list(null, null, null, "ACTIVE", 0, 0);

    assertThat(result).isNotNull();
  }

  @Test
  void list_Should_AutoExpireLinks_When_NoStatusFilter_And_LinkIsExpired() {
    AgencyInviteLink expiredLink =
        AgencyInviteLink.builder()
            .token("t")
            .status(InviteLinkStatus.ACTIVE.name())
            .tenantId(1L)
            .expiresAt(LocalDateTime.now().minusHours(1))
            .build();
    var page = new org.springframework.data.domain.PageImpl<>(java.util.List.of(expiredLink));
    when(repository.findAllByTenantIdAndFilters(any(), any(), any(), any(), any(), any()))
        .thenReturn(page);
    when(repository.save(any())).thenReturn(expiredLink);

    service.list(null, null, null, null, 0, 20);

    verify(repository).save(expiredLink);
    assertThat(expiredLink.getStatus()).isEqualTo(InviteLinkStatus.EXPIRED.name());
  }

  @Test
  void redeemWithContext_Should_Delegate_To_Redeem() {
    AgencyInviteLink link =
        AgencyInviteLink.builder()
            .token("tok")
            .status(InviteLinkStatus.ACTIVE.name())
            .tenantId(1L)
            .agencyId(7L)
            .consultingTypeId(3)
            .build();
    when(repository.findByTokenAndStatus("tok", InviteLinkStatus.ACTIVE.name()))
        .thenReturn(Optional.of(link));

    var ctx = service.redeemWithContext("tok");

    assertThat(ctx).isNotNull();
    assertThat(ctx.getAgencyId()).isEqualTo(7L);
  }

  @Test
  void redeem_Should_ReturnContext_When_NoAgencyId_FallsBackToConsultingType() {
    AgencyInviteLink link =
        AgencyInviteLink.builder()
            .token("tok2")
            .status(InviteLinkStatus.ACTIVE.name())
            .tenantId(1L)
            .consultingTypeId(5)
            .build();
    AgencyDTO fallbackAgency = new AgencyDTO();
    fallbackAgency.setId(42L);
    when(repository.findByTokenAndStatus("tok2", InviteLinkStatus.ACTIVE.name()))
        .thenReturn(Optional.of(link));
    when(agencyService.getAgenciesByConsultingType(5))
        .thenReturn(java.util.List.of(fallbackAgency));

    var ctx = service.redeem("tok2");

    assertThat(ctx.getAgencyId()).isEqualTo(42L);
  }

  @Test
  void redeem_Should_ThrowBadRequest_When_LinkIsNotActive() {
    AgencyInviteLink inactiveLink =
        AgencyInviteLink.builder()
            .token("tok3")
            .status(InviteLinkStatus.EXPIRED.name())
            .tenantId(1L)
            .build();
    when(repository.findByTokenAndStatus("tok3", InviteLinkStatus.ACTIVE.name()))
        .thenReturn(Optional.empty());
    when(repository.findByToken("tok3")).thenReturn(Optional.of(inactiveLink));

    assertThatThrownBy(() -> service.redeem("tok3"))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("not active");
  }

  @Test
  void pickConsultingTypeId_Should_ThrowInternalError_When_NoConsultingTypesConfigured() {
    AgencyInviteLink link =
        AgencyInviteLink.builder()
            .token("tok4")
            .status(InviteLinkStatus.ACTIVE.name())
            .tenantId(1L)
            .agencyId(10L)
            .build();
    AgencyDTO agency = new AgencyDTO();
    agency.setId(10L);
    when(repository.findByTokenAndStatus("tok4", InviteLinkStatus.ACTIVE.name()))
        .thenReturn(Optional.of(link));
    when(consultingTypeService.getAllConsultingTypeIds(1L)).thenReturn(java.util.List.of());

    assertThatThrownBy(() -> service.redeem("tok4"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException
                .class);
  }
}
