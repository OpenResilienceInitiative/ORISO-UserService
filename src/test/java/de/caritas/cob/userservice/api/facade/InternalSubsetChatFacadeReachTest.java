package de.caritas.cob.userservice.api.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.chat.GroupChatFeatureGate;
import de.caritas.cob.userservice.api.service.chat.InternalGroupChatRecipientScope;
import de.caritas.cob.userservice.api.service.chat.InternalGroupChatRecipientScopeResolver;
import de.caritas.cob.userservice.api.service.chat.MemberSetLock;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Whether a calendar appointment with a colleague can have an internal chat (FE#2305). */
@ExtendWith(MockitoExtension.class)
class InternalSubsetChatFacadeReachTest {

  @Mock MatrixConfig matrixConfig;
  @Mock GroupChatFeatureGate featureGate;
  @Mock InternalGroupChatRecipientScopeResolver recipientScopes;
  @Mock ConsultantRepository consultantRepository;
  @Mock AdminRepository adminRepository;
  @Mock ChatRepository chatRepository;
  @Mock GroupChatParticipantRepository participantRepository;
  @Mock MatrixSynapseService matrixSynapseService;
  @Mock MemberSetLock memberSetLock;
  @Mock CreateChatFacade createChatFacade;
  @Mock AgencyService agencyService;
  @Mock TenantService tenantService;
  @InjectMocks InternalSubsetChatFacade facade;

  private final Consultant me = consultant("me", 1L, 10L);
  private final Consultant sameCentre = consultant("same", 1L, 10L);
  private final Consultant otherCentre = consultant("other", 1L, 20L);

  @BeforeEach
  void setUp() {
    lenient().when(matrixConfig.isEncryptionEnabled()).thenReturn(true);
    lenient().when(recipientScopes.resolve(1L)).thenReturn(InternalGroupChatRecipientScope.AGENCY);
  }

  @Test
  void reachableColleagues_Should_FollowTheRecipientScope() {
    assertThat(facade.reachableColleagues(me, List.of(sameCentre, otherCentre)))
        .containsExactly("same");
  }

  @Test
  void reachableColleagues_Should_BeEmpty_When_InternalChatsAreSwitchedOff() {
    doThrow(new ForbiddenException("off")).when(featureGate).requireEnabled(any(), any());

    assertThat(facade.reachableColleagues(me, List.of(sameCentre))).isEmpty();
  }

  @Test
  void reachableColleagues_Should_BeEmpty_Without_Encryption() {
    when(matrixConfig.isEncryptionEnabled()).thenReturn(false);

    assertThat(facade.reachableColleagues(me, List.of(sameCentre))).isEmpty();
  }

  @Test
  void reachableColleagues_Should_OnlyCountCentresWhereChatsAreOn() {
    var both = consultant("me2", 1L, 10L, 20L);
    // Lenient: a strict mismatch would itself throw and look like "switched off".
    lenient()
        .doThrow(new ForbiddenException("off"))
        .when(featureGate)
        .requireEnabled(any(), argThat(chat -> chat != null && chat.getAgencyId() == 20L));

    assertThat(facade.reachableColleagues(both, List.of(sameCentre, otherCentre)))
        .containsExactly("same");
  }

  private static Consultant consultant(String id, Long tenantId, Long... agencyIds) {
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setTenantId(tenantId);
    Set<ConsultantAgency> agencies =
        java.util.Arrays.stream(agencyIds)
            .map(
                agencyId ->
                    ConsultantAgency.builder().id(agencyId * 100).agencyId(agencyId).build())
            .collect(Collectors.toSet());
    consultant.setConsultantAgencies(agencies);
    return consultant;
  }
}
