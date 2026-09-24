package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.conversation.facade.CreateAnonymousEnquiryFacade;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgencyInviteLinkContextTest {
  @Mock private AgencyInviteLinkRepository repository;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private TopicService topicService;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private ConsultingTypeService consultingTypeService;
  @Mock private AgencyService agencyService;
  @Mock private CreateAnonymousEnquiryFacade createAnonymousEnquiryFacade;
  @InjectMocks private AgencyInviteLinkService service;

  @Mock
  private de.caritas.cob.userservice.api.admin.service.admin.AdminCallerScope adminCallerScope;

  @AfterEach
  void noWritesOrProvisioning() {
    verify(repository).findByToken(org.mockito.ArgumentMatchers.anyString());
    verifyNoMoreInteractions(repository);
    verifyNoInteractions(
        createAnonymousEnquiryFacade, authenticatedUser, consultantRepository, topicService);
    TenantContext.clear();
  }

  @Test
  void liveContextDoesNotBindAgencyOrChangeLinkAndRestoresTenant() {
    var link = link("LIVE_CHAT");
    link.setAgencyId(99L);
    TenantContext.setCurrentTenant(7L);
    stub(link);
    var result = service.getContext("token");
    assertThat(result.tenantId()).isEqualTo(83L);
    assertThat(result.agencyId()).isNull();
    assertThat(result.consultingTypeId()).isEqualTo(1);
    assertThat(result.topicId()).isEqualTo(11L);
    assertThat(result.chatType()).isEqualTo("LIVE_CHAT");
    assertThat(link.getStatus()).isEqualTo("ACTIVE");
    assertThat(link.getUsedAt()).isNull();
    assertThat(link.getUsedBySessionId()).isNull();
    assertThat(TenantContext.getCurrentTenant()).isEqualTo(7L);
    verifyNoInteractions(agencyService, consultingTypeService);
  }

  @Test
  void legacyContextUsesExplicitAgencyAndClearsTemporaryTenant() {
    var link = link("REGISTERED");
    link.setAgencyId(5L);
    TenantContext.clear();
    stub(link);
    assertThat(service.getContext("token").agencyId()).isEqualTo(5L);
    assertThat(TenantContext.getCurrentTenant()).isNull();
  }

  @Test
  void legacyFallbackUsesLinkTenantConsultingTypeAndMatchingTopic() {
    var link = link("REGISTERED");
    link.setConsultingTypeId(null);
    stub(link);
    when(consultingTypeService.getAllConsultingTypeIds(83L))
        .thenAnswer(
            invocation -> {
              assertThat(TenantContext.getCurrentTenant()).isEqualTo(83L);
              return List.of(2);
            });
    when(agencyService.getAgenciesByConsultingType(2))
        .thenReturn(
            List.of(
                new AgencyDTO().id(1L).tenantId(1L).topicIds(List.of(11L)),
                new AgencyDTO().id(2L).tenantId(83L).topicIds(List.of(12L)),
                new AgencyDTO().id(3L).tenantId(83L).topicIds(List.of(11L))));
    var result = service.getContext("token");
    assertThat(result.agencyId()).isEqualTo(3L);
    assertThat(result.consultingTypeId()).isEqualTo(2);
  }

  @Test
  void expiredDateDoesNotPersistOrMutateExpiryStatus() {
    var link = link("LIVE_CHAT");
    link.setExpiresAt(LocalDateTime.now().minusSeconds(1));
    stub(link);
    assertThatThrownBy(() -> service.getContext("token")).isInstanceOf(BadRequestException.class);
    assertThat(link.getStatus()).isEqualTo("ACTIVE");
  }

  @ParameterizedTest
  @ValueSource(strings = {"USED", "EXPIRED", "REVOKED"})
  void inactiveContextIsRejected(String status) {
    var link = link("LIVE_CHAT");
    link.setStatus(status);
    stub(link);
    assertThatThrownBy(() -> service.getContext("token")).isInstanceOf(BadRequestException.class);
    assertThat(link.getStatus()).isEqualTo(status);
  }

  @Test
  void missingTokenIsNotFound() {
    when(repository.findByToken("missing")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.getContext("missing")).isInstanceOf(NotFoundException.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void consultingTypeFailureRestoresPriorTenant(boolean hasPriorTenant) {
    var link = link("LIVE_CHAT");
    link.setConsultingTypeId(null);
    stub(link);
    if (hasPriorTenant) TenantContext.setCurrentTenant(7L);
    else TenantContext.clear();
    when(consultingTypeService.getAllConsultingTypeIds(83L))
        .thenThrow(new IllegalStateException("unavailable"));
    assertThatThrownBy(() -> service.getContext("token")).isInstanceOf(IllegalStateException.class);
    assertThat(TenantContext.getCurrentTenant()).isEqualTo(hasPriorTenant ? 7L : null);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void restoresCompleteContextWithNullTenantIdOnSuccessAndFailure(boolean failLookup) {
    var caller = new TenantData(null, "caller");
    TenantContext.setCurrentTenantData(caller);
    var link = link("LIVE_CHAT");
    link.setConsultingTypeId(null);
    stub(link);
    when(consultingTypeService.getAllConsultingTypeIds(83L))
        .thenAnswer(
            invocation -> {
              assertThat(TenantContext.getCurrentTenantData()).isNotSameAs(caller);
              assertThat(TenantContext.getCurrentTenant()).isEqualTo(83L);
              assertThat(TenantContext.getCurrentTenantData().getSubdomain()).isNull();
              assertThat(caller.getTenantId()).isNull();
              if (failLookup) {
                throw new IllegalStateException("unavailable");
              }
              return List.of(1);
            });

    if (failLookup) {
      assertThatThrownBy(() -> service.getContext("token"))
          .isInstanceOf(IllegalStateException.class);
    } else {
      assertThat(service.getContext("token").tenantId()).isEqualTo(83L);
    }

    assertThat(TenantContext.getCurrentTenantData()).isSameAs(caller);
    assertThat(caller.getTenantId()).isNull();
    assertThat(caller.getSubdomain()).isEqualTo("caller");
  }

  @Test
  void agencyFailureRestoresPriorTenant() {
    stub(link("REGISTERED"));
    TenantContext.setCurrentTenant(7L);
    when(agencyService.getAgenciesByConsultingType(1)).thenReturn(List.of());
    assertThatThrownBy(() -> service.getContext("token")).isInstanceOf(BadRequestException.class);
    assertThat(TenantContext.getCurrentTenant()).isEqualTo(7L);
  }

  private void stub(AgencyInviteLink link) {
    when(repository.findByToken("token")).thenReturn(Optional.of(link));
  }

  private AgencyInviteLink link(String chatType) {
    return AgencyInviteLink.builder()
        .token("token")
        .tenantId(83L)
        .consultingTypeId(1)
        .topicId(11L)
        .chatType(chatType)
        .status("ACTIVE")
        .expiresAt(LocalDateTime.now().plusDays(1))
        .build();
  }
}
