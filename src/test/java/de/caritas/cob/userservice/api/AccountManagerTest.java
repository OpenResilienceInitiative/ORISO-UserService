package de.caritas.cob.userservice.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

@ExtendWith(MockitoExtension.class)
class AccountManagerTest {

  @InjectMocks AccountManager accountManager;

  @Mock ConsultantRepository consultantRepository;

  @Mock de.caritas.cob.userservice.api.port.out.AdminRepository adminRepository;

  @Mock ConsultantAgencyRepository consultantAgencyRepository;

  @Mock UserServiceMapper userServiceMapper;

  @Mock AgencyService agencyService;

  @Mock TenantService tenantService;

  @Mock Page<Consultant.ConsultantBase> page;

  // AccountManager#resolveEffectiveTenantId falls back to the thread-bound TenantContext when the
  // access token carries no tenant (the mocked AuthenticatedUser returns a null token here). These
  // tests stub the repository for the no-tenant case (effectiveTenantId == null), so any tenant
  // leaked by an earlier test in the suite would make the stubbed call mismatch under strict
  // stubbing. Clear the context around every test to keep them hermetic regardless of run order.
  @BeforeEach
  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  @Test
  void findConsultantsByInfix_Should_NotFilterByAgenciesIfAgencyListIsEmpty() {
    // given
    Mockito.when(
            consultantRepository.findAllByInfix(
                Mockito.eq("infix"), Mockito.isNull(), Mockito.any(PageRequest.class)))
        .thenReturn(page);

    // when
    accountManager.findConsultantsByInfix(
        "infix", false, Lists.newArrayList(), 1, 10, "email", true);

    // then
    Mockito.verify(consultantRepository)
        .findAllByInfix(Mockito.eq("infix"), Mockito.isNull(), Mockito.any(PageRequest.class));
  }

  @Test
  void findConsultantsByInfix_Should_NotFail_When_AgencyServiceRespondsWithClientError() {
    // given: consultants whose agencies were all deleted make the AgencyService
    // bulk lookup respond with 404, raised by the generated client as a client error
    Mockito.when(
            consultantRepository.findAllByInfix(
                Mockito.eq("infix"), Mockito.isNull(), Mockito.any(PageRequest.class)))
        .thenReturn(page);
    Mockito.when(agencyService.getAgenciesWithoutCaching(Mockito.anyList()))
        .thenThrow(
            HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

    // when
    assertDoesNotThrow(
        () ->
            accountManager.findConsultantsByInfix(
                "infix", false, Lists.newArrayList(), 1, 10, "email", true));

    // then: the listing is still mapped, just without agency data
    Mockito.verify(userServiceMapper)
        .mapOf(
            Mockito.eq(page),
            Mockito.anyList(),
            Mockito.eq(List.of()),
            Mockito.anyList(),
            Mockito.anyMap(),
            Mockito.any());
  }

  @Test
  void findConsultantsByInfix_Should_OnlyResolveAgenciesOfNotDeletedRelations() {
    // given
    Mockito.when(
            consultantRepository.findAllByInfix(
                Mockito.eq("infix"), Mockito.isNull(), Mockito.any(PageRequest.class)))
        .thenReturn(page);

    // when
    accountManager.findConsultantsByInfix(
        "infix", false, Lists.newArrayList(), 1, 10, "email", true);

    // then: soft-deleted consultant-agency relations must not be resolved at all
    Mockito.verify(consultantAgencyRepository)
        .findByConsultantIdInAndDeleteDateIsNull(Mockito.<List<String>>any());
  }

  @Test
  void findConsultantsByInfix_Should_FilterByAgenciesIfAgencyListIsNotEmpty() {
    // given
    Mockito.when(
            consultantRepository.findAllByInfixAndAgencyIds(
                Mockito.eq("infix"),
                Mockito.anyCollection(),
                Mockito.isNull(),
                Mockito.any(PageRequest.class)))
        .thenReturn(page);

    // when
    accountManager.findConsultantsByInfix(
        "infix", true, Lists.newArrayList(1L), 1, 10, "email", true);

    // then
    Mockito.verify(consultantRepository)
        .findAllByInfixAndAgencyIds(
            Mockito.eq("infix"),
            Mockito.eq(Lists.newArrayList(1L)),
            Mockito.isNull(),
            Mockito.any(PageRequest.class));
  }
}
