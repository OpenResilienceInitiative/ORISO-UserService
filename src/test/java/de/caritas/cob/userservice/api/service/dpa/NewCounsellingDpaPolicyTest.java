package de.caritas.cob.userservice.api.service.dpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.DpaGateStatusDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

/** #1704: the consultant's own read of the AVV decision that blocks new counselling. */
@ExtendWith(MockitoExtension.class)
class NewCounsellingDpaPolicyTest {

  private static final Long TENANT_ID = 7L;

  @Mock private TenantDpaGateReadClient owner;
  @Mock private AgencyService agencyService;
  @Mock private TenantService tenantService;

  @InjectMocks private NewCounsellingDpaPolicy policy;

  @Test
  void allowsNewCounselling_Should_BeTrue_When_CurrentAgreementIsSigned() {
    when(owner.read(TENANT_ID))
        .thenReturn(new DpaGateStatusDTO().dpaPublished(true).dpaSigned(true));

    assertEquals(Boolean.TRUE, policy.allowsNewCounselling(TENANT_ID));
  }

  @Test
  void allowsNewCounselling_Should_BeFalse_When_ProviderOnlySignedAnOlderVersion() {
    when(owner.read(TENANT_ID))
        .thenReturn(
            new DpaGateStatusDTO()
                .dpaPublished(true)
                .dpaSigned(false)
                .dpaStatus("OUTDATED")
                .currentDpaVersion("2026-10-01T07:52:52")
                .renewalGraceActive(false)
                .newCounsellingAllowed(false));

    assertEquals(Boolean.FALSE, policy.allowsNewCounselling(TENANT_ID));
  }

  @Test
  void allowsNewCounselling_Should_BeUnknown_When_OwnerCannotBeRead() {
    when(owner.read(TENANT_ID)).thenThrow(new RestClientException("owner down"));

    assertNull(policy.allowsNewCounselling(TENANT_ID));
  }

  @Test
  void allowsNewCounselling_Should_BeUnknown_When_ConsultantHasNoProvider() {
    assertNull(policy.allowsNewCounselling(null));
    verifyNoInteractions(owner);
  }
}
