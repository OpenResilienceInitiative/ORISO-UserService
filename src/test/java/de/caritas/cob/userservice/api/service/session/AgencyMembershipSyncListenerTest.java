package de.caritas.cob.userservice.api.service.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * US#1060: the membership fan-out must never ride along inside the caller's transaction.
 *
 * <p>{@code ConsultantAdminFacade#setConsultantAgencies} is transactional and applies removals
 * before creations. A synchronous fan-out would push the first agency's membership into Matrix and
 * then, if a later agency is rejected, roll the database back around it — leaving the counsellor a
 * member of the enquiry rooms of an agency they were never given. Matrix has no transaction to
 * join, so the only correct moment is after the commit that made the relation real.
 *
 * <p>The second reason for the listener is blast radius: {@code
 * CreateConsultantSaga#assignAgenciesOrRollback} deletes the freshly created consultant on any
 * {@link RuntimeException}. A Synapse hiccup must therefore never escape this listener.
 */
@ExtendWith(MockitoExtension.class)
class AgencyMembershipSyncListenerTest {

  private static final String CONSULTANT_ID = "c-late";
  private static final Long AGENCY_ID = 4711L;

  @Mock private ConsultantRepository consultantRepository;
  @Mock private AgencyLateJoinerMembershipService agencyLateJoinerMembershipService;

  @InjectMocks private AgencyMembershipSyncListener underTest;

  private final Consultant consultant = new Consultant();

  @BeforeEach
  void setUp() {
    consultant.setId(CONSULTANT_ID);
    ReflectionTestUtils.setField(underTest, "lateJoinerMembershipEnabled", true);
  }

  @Test
  @DisplayName("a committed agency assignment backfills the counsellor into the open enquiries")
  void onConsultantJoinedAgency_resolvesTheConsultantAndJoinsTheOpenEnquiries() {
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.of(consultant));

    underTest.onConsultantJoinedAgency(new ConsultantJoinedAgencyEvent(CONSULTANT_ID, AGENCY_ID));

    verify(agencyLateJoinerMembershipService)
        .joinConsultantIntoOpenEnquiryRooms(consultant, AGENCY_ID);
  }

  @Test
  @DisplayName("a Matrix failure must never roll back the consultant CreateConsultantSaga created")
  void onConsultantJoinedAgency_neverThrows_When_theFanOutBlowsUp() {
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.of(consultant));
    doThrow(new RuntimeException("synapse is down"))
        .when(agencyLateJoinerMembershipService)
        .joinConsultantIntoOpenEnquiryRooms(any(), anyLong());

    assertDoesNotThrow(
        () ->
            underTest.onConsultantJoinedAgency(
                new ConsultantJoinedAgencyEvent(CONSULTANT_ID, AGENCY_ID)));
  }

  @Test
  @DisplayName("a consultant deleted between commit and fan-out is simply skipped")
  void onConsultantJoinedAgency_doesNothing_When_theConsultantWasDeletedMeanwhile() {
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.empty());

    underTest.onConsultantJoinedAgency(new ConsultantJoinedAgencyEvent(CONSULTANT_ID, AGENCY_ID));

    verifyNoInteractions(agencyLateJoinerMembershipService);
  }

  @Test
  @DisplayName("operations can switch the fan-out off for a bulk import window")
  void onConsultantJoinedAgency_doesNothing_When_theLateJoinerHookIsDisabled() {
    ReflectionTestUtils.setField(underTest, "lateJoinerMembershipEnabled", false);

    underTest.onConsultantJoinedAgency(new ConsultantJoinedAgencyEvent(CONSULTANT_ID, AGENCY_ID));

    verifyNoInteractions(consultantRepository, agencyLateJoinerMembershipService);
  }

  @Test
  @DisplayName("a committed agency removal revokes the counsellor's open enquiry membership")
  void onConsultantLeftAgency_resolvesTheConsultantAndRevokesTheOpenEnquiries() {
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.of(consultant));

    underTest.onConsultantLeftAgency(new ConsultantLeftAgencyEvent(CONSULTANT_ID, AGENCY_ID));

    verify(agencyLateJoinerMembershipService)
        .removeConsultantFromOpenEnquiryRooms(consultant, AGENCY_ID);
  }

  @Test
  @DisplayName("a failing revocation must not turn a successful detach into an error response")
  void onConsultantLeftAgency_neverThrows_When_theRevocationBlowsUp() {
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.of(consultant));
    doThrow(new RuntimeException("synapse is down"))
        .when(agencyLateJoinerMembershipService)
        .removeConsultantFromOpenEnquiryRooms(any(), anyLong());

    assertDoesNotThrow(
        () ->
            underTest.onConsultantLeftAgency(
                new ConsultantLeftAgencyEvent(CONSULTANT_ID, AGENCY_ID)));
  }

  @Test
  @DisplayName("the kill switch stops the revocation half as well")
  void onConsultantLeftAgency_doesNothing_When_theLateJoinerHookIsDisabled() {
    ReflectionTestUtils.setField(underTest, "lateJoinerMembershipEnabled", false);

    underTest.onConsultantLeftAgency(new ConsultantLeftAgencyEvent(CONSULTANT_ID, AGENCY_ID));

    verifyNoInteractions(consultantRepository, agencyLateJoinerMembershipService);
  }
}
