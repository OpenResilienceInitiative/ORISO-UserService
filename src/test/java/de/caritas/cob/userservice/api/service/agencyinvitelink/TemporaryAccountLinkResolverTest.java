package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
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
class TemporaryAccountLinkResolverTest {

  @Mock private AgencyInviteLinkRepository repository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private DirectInvitePolicy directInvitePolicy;
  @Mock private CallShareLinkService callShareLinks;
  @InjectMocks private TemporaryAccountLinkResolver resolver;

  private AgencyInviteLink link;

  @BeforeEach
  void setUp() {
    link =
        AgencyInviteLink.builder()
            .id(9L)
            .token("tok")
            .tenantId(5L)
            .agencyId(99L)
            .linkKind("COUNSELLOR")
            .chatType("VIDEO")
            .status("ACTIVE")
            .consultantId("owner")
            .temporaryAccount(true)
            .build();
    when(repository.findByToken("tok")).thenReturn(Optional.of(link));
    var owner = new Consultant();
    owner.setId("owner");
    owner.setConsultantAgencies(
        new HashSet<>(Set.of(ConsultantAgency.builder().id(1L).agencyId(99L).build())));
    when(consultantRepository.findByIdAndDeleteDateIsNull("owner")).thenReturn(Optional.of(owner));
    when(directInvitePolicy.allows(5L, 99L, InviteLinkChatType.VIDEO)).thenReturn(true);
    TenantContext.setCurrentTenant(5L);
  }

  /** A withdrawn direct-invite permission refuses before any account exists. */
  @Test
  void resolve_Should_Refuse_When_DirectInvitationsAreNotAllowed() {
    when(directInvitePolicy.allows(5L, 99L, InviteLinkChatType.VIDEO)).thenReturn(false);

    assertThatThrownBy(() -> resolver.resolve(registration("tok", true)))
        .isInstanceOf(DirectLinkInactiveException.class);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  private static UserDTO registration(String token, boolean temporary) {
    var dto = new UserDTO();
    dto.setDirectLinkToken(token);
    dto.setTemporary(temporary);
    return dto;
  }

  @Test
  void resolve_Should_ReturnEmpty_ForAnOrdinaryRegistration() {
    assertThat(resolver.resolve(registration(null, false))).isEmpty();
    verifyNoInteractions(repository);
  }

  @Test
  void resolve_Should_ReturnTheLink_When_ItCreatesTemporaryAccounts() {
    assertThat(resolver.resolve(registration("tok", true))).contains(link);
  }

  @Test
  void acceptsRegistrationForTheLiveSharedCall() {
    var registration = callRegistration(true);

    assertThat(resolver.resolve(registration)).contains(link);
  }

  @Test
  void rejectsRegistrationIfTheSharedCallEnded() {
    var registration = callRegistration(true);
    doThrow(new CallShareLinkEndedException()).when(callShareLinks).requireUsable(link);

    assertThatThrownBy(() -> resolver.resolve(registration))
        .isInstanceOf(CallShareLinkEndedException.class);
  }

  @Test
  void rejectsRegistrationWithAnotherAgencysConsentContext() {
    var registration = callRegistration(true);
    registration.setAgencyId(100L);

    assertThatThrownBy(() -> resolver.resolve(registration)).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void refusesTemporaryRegistrationAfterPermissionWasWithdrawn() {
    var registration = callRegistration(true);
    when(callShareLinks.temporaryAccountAllowed(link)).thenReturn(false);

    assertThatThrownBy(() -> resolver.resolve(registration))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void rejectsCallRegistrationWithoutTermsAcceptance() {
    var registration = callRegistration(true);
    registration.setTermsAccepted(null);

    assertThatThrownBy(() -> resolver.resolve(registration))
        .isInstanceOf(BadRequestException.class);
  }

  private UserDTO callRegistration(boolean temporary) {
    link.setLinkKind("CALL");
    link.setCallBindingId(11L);
    link.setConsultingTypeId(2);
    link.setTopicId(3L);
    when(callShareLinks.temporaryAccountAllowed(link)).thenReturn(true);
    var registration = registration("tok", temporary);
    registration.setAgencyId(99L);
    registration.setConsultingType("2");
    registration.setTermsAccepted("true");
    registration.setMainTopicId(3L);
    return registration;
  }

  @Test
  void resolve_Should_RequireTheTemporaryFlag_When_TheInvitationIsForGuestsOnly() {
    link.setAccessMode(DirectInvitationAccessMode.GUESTS_ONLY);
    assertThatThrownBy(() -> resolver.resolve(registration("tok", false)))
        .isInstanceOf(BadRequestException.class);
  }

  /**
   * ORISO-Frontend#2252: a link that creates temporary accounts reads "both" (the person chooses),
   * so a permanent registration through it is allowed as well.
   */
  @Test
  void resolve_Should_LetThePersonChoose_When_TheInvitationIsForBoth() {
    assertThat(resolver.resolve(registration("tok", false))).contains(link);
    assertThat(resolver.resolve(registration("tok", true))).contains(link);
  }

  @Test
  void resolve_Should_RefuseATemporaryRegistration_When_TheInvitationIsForAccountsOnly() {
    link.setAccessMode(DirectInvitationAccessMode.ACCOUNTS_ONLY);
    assertThatThrownBy(() -> resolver.resolve(registration("tok", true)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void resolve_Should_SayExpired_ForAnExpiredInvitation_AndAReplacedToken() {
    link.setExpiresAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
    assertThatThrownBy(() -> resolver.resolve(registration("tok", true)))
        .isInstanceOf(DirectLinkExpiredException.class);

    when(repository.findFirstByPreviousToken("old")).thenReturn(Optional.of(link));
    assertThatThrownBy(() -> resolver.resolve(registration("old", true)))
        .isInstanceOf(DirectLinkExpiredException.class);
  }

  @Test
  void resolve_Should_ReturnTheLink_ForAPersistentAccount_When_TheLinkCreatesNoTemporaryAccounts() {
    link.setTemporaryAccount(false);

    assertThat(resolver.resolve(registration("tok", false))).contains(link);
  }

  @Test
  void resolve_Should_Refuse_When_TheLinkDoesNotCreateTemporaryAccounts() {
    link.setTemporaryAccount(false);

    assertThatThrownBy(() -> resolver.resolve(registration("tok", true)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void resolve_Should_AnswerGone_When_TheLinkIsSwitchedOff() {
    link.setStatus("DISABLED");

    assertThatThrownBy(() -> resolver.resolve(registration("tok", true)))
        .isInstanceOf(DirectLinkInactiveException.class);
  }

  @Test
  void resolve_Should_Refuse_AnUnknownTokenOrAnAdminLink() {
    assertThatThrownBy(() -> resolver.resolve(registration("unknown", true)))
        .isInstanceOf(ForbiddenException.class);
    link.setLinkKind("TENANT");
    link.setChatType("LIVE_CHAT");
    assertThatThrownBy(() -> resolver.resolve(registration("tok", true)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void resolve_Should_Refuse_ALinkOfAnotherTenant() {
    TenantContext.setCurrentTenant(6L);

    assertThatThrownBy(() -> resolver.resolve(registration("tok", true)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void resolve_Should_AnswerGone_When_TheOwnerLeftTheBeratungsstelle() {
    when(consultantRepository.findByIdAndDeleteDateIsNull("owner")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> resolver.resolve(registration("tok", true)))
        .isInstanceOf(DirectLinkInactiveException.class);
  }

  @Test
  void resolve_Should_Refuse_ACombinationWithAGroupInvite() {
    var dto = registration("tok", true);
    dto.setGroupChatId(19L);

    assertThatThrownBy(() -> resolver.resolve(dto)).isInstanceOf(BadRequestException.class);
  }
}
