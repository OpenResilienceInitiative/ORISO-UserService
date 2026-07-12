package de.caritas.cob.userservice.api.conversation.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.conversation.model.PageableListRequest;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.sessionlist.ConsultantSessionEnricher;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The anonymous Live Chat queue is deliberately cross-agency AND cross-tenant: any consultant who
 * is live for a topic/consulting type must see anonymous enquiries for that topic regardless of the
 * asker's tenant. Tenant isolation on Session queries is enforced by {@code TenantAspect}, which
 * enables the Hibernate {@code tenantFilter} for the current tenant and disables it only in
 * technical context. This test pins that the two queue queries run in technical context (filter
 * off), and that the caller's tenant context is restored afterwards so no other query leaks.
 */
@ExtendWith(MockitoExtension.class)
class AnonymousEnquiryConversationListProviderCrossTenantTest {

  private static final Long CONSULTANT_TENANT = 83L;

  @Mock private UserAccountService userAccountProvider;
  @Mock private SessionRepository sessionRepository;
  @Mock private AgencyService agencyService;
  @Mock private ConsultantSessionEnricher consultantSessionEnricher;
  @Mock private ConsultantTopicRepository consultantTopicRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private AnonymousEnquiryConversationListProvider newProvider() {
    var provider =
        new AnonymousEnquiryConversationListProvider(
            userAccountProvider,
            sessionRepository,
            agencyService,
            consultantSessionEnricher,
            consultantTopicRepository);
    ReflectionTestUtils.setField(provider, "liveChatQueueActivePeriodMinutes", 60L);
    return provider;
  }

  private Consultant consultantWithAgency() {
    var consultant = org.mockito.Mockito.mock(Consultant.class);
    var agency = org.mockito.Mockito.mock(ConsultantAgency.class);
    lenient().when(agency.getAgencyId()).thenReturn(1L);
    lenient().when(consultant.getId()).thenReturn("consultant-83");
    lenient().when(consultant.getConsultantAgencies()).thenReturn(Set.of(agency));
    return consultant;
  }

  @Test
  void buildConversations_Should_runTopicQueueQueryCrossTenant_And_restoreTenant() {
    TenantContext.setCurrentTenant(CONSULTANT_TENANT);
    var consultant = consultantWithAgency();
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);
    when(agencyService.getAgencies(any())).thenReturn(List.of(new AgencyDTO().consultingType(1)));
    when(consultantTopicRepository.findTopicIdsByConsultantId("consultant-83"))
        .thenReturn(List.of(11L));

    var tenantDuringQuery = new AtomicReference<Long>();
    when(sessionRepository.findAnonymousEnquiriesVisibleForConsultantsByTopics(
            anySet(), anySet(), any(), any(), any(), any(Pageable.class)))
        .thenAnswer(
            invocation -> {
              tenantDuringQuery.set(TenantContext.getCurrentTenant());
              return new PageImpl<Session>(List.of());
            });

    newProvider().buildConversations(PageableListRequest.builder().count(5).offset(0).build());

    assertThat(tenantDuringQuery.get()).isEqualTo(TenantContext.TECHNICAL_TENANT_ID);
    assertThat(TenantContext.getCurrentTenant()).isEqualTo(CONSULTANT_TENANT);
  }

  @Test
  void buildConversations_Should_runNoTopicQueueQueryCrossTenant_And_restoreTenant() {
    TenantContext.setCurrentTenant(CONSULTANT_TENANT);
    var consultant = consultantWithAgency();
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);
    when(agencyService.getAgencies(any())).thenReturn(List.of(new AgencyDTO().consultingType(1)));
    when(consultantTopicRepository.findTopicIdsByConsultantId("consultant-83"))
        .thenReturn(List.of());

    var tenantDuringQuery = new AtomicReference<Long>();
    when(sessionRepository.findAnonymousEnquiriesVisibleForConsultantsWithoutTopic(
            anySet(), any(), any(), any(), any(Pageable.class)))
        .thenAnswer(
            invocation -> {
              tenantDuringQuery.set(TenantContext.getCurrentTenant());
              return new PageImpl<Session>(List.of());
            });

    newProvider().buildConversations(PageableListRequest.builder().count(5).offset(0).build());

    assertThat(tenantDuringQuery.get()).isEqualTo(TenantContext.TECHNICAL_TENANT_ID);
    assertThat(TenantContext.getCurrentTenant()).isEqualTo(CONSULTANT_TENANT);
  }
}
