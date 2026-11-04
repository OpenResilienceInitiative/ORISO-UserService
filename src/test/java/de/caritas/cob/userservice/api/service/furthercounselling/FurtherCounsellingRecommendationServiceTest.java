package de.caritas.cob.userservice.api.service.furthercounselling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FurtherCounsellingRecommendationServiceTest {

  private static final long SESSION_ID = 11L;
  private static final long TOPIC_ID = 3L;

  @Mock SessionRepository sessionRepository;
  @Mock AuthenticatedUser authenticatedUser;
  @Mock MembersOnlyChatAccess membersOnlyChatAccess;
  @Mock TopicService topicService;
  @Mock ConsultantDisplayNameResolver displayNames;
  @Mock MatrixSessionSystemMessageService systemMessages;
  @InjectMocks FurtherCounsellingRecommendationService service;

  private Session session;

  @BeforeEach
  void setUp() {
    var consultant = new Consultant();
    consultant.setId("assigned");
    var user = new User();
    user.setUserId("asker");
    user.setUsername("asker-login");
    user.setEmail("asker@example.org");
    session = new Session();
    session.setId(SESSION_ID);
    session.setConsultant(consultant);
    session.setUser(user);
    session.setStatus(SessionStatus.IN_PROGRESS);
    session.setConversationType(ConversationType.AGENCY_COUNSELLING);
    session.setPostcode("79098");
    session.setMatrixRoomId("!room:matrix");
    lenient().when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    lenient().when(authenticatedUser.getUserId()).thenReturn("assigned");
    lenient().when(topicService.getTopicById(TOPIC_ID)).thenReturn(new TopicDTO().id(TOPIC_ID));
    lenient()
        .when(displayNames.resolveMatrixDisplayName(any(Consultant.class)))
        .thenReturn("Frau Berg");
    lenient()
        .when(systemMessages.postSystemMessage(any(), anyString(), anyBoolean()))
        .thenReturn(true);
    lenient().when(systemMessages.hasConsultantMatrixIdentity(session)).thenReturn(true);
  }

  @Test
  void consultantWithoutMatrixIdentityIsAConflictAndNothingIsPosted() {
    // Never fall back to the asker's account: the card must come from the counsellor.
    when(systemMessages.hasConsultantMatrixIdentity(session)).thenReturn(false);

    assertThatThrownBy(() -> service.recommend(SESSION_ID, TOPIC_ID, null))
        .isInstanceOf(ConflictException.class);
    verify(systemMessages, never()).postSystemMessage(any(), anyString(), anyBoolean());
  }

  @Test
  void postsTheRecommendationAsTheAssignedConsultant() {
    service.recommend(SESSION_ID, TOPIC_ID, "  Bitte melden Sie sich.  ");

    var body = ArgumentCaptor.forClass(String.class);
    verify(systemMessages).postSystemMessage(eq(session), body.capture(), eq(true));
    assertThat(body.getValue())
        .startsWith("[SYSTEM_NOTIFICATION]{\"type\":\"FURTHER_COUNSELLING_RECOMMENDED\"")
        .contains("\"topicId\":3")
        .contains("\"note\":\"Bitte melden Sie sich.\"")
        .contains("\"recommendedBy\":\"Frau Berg\"")
        .contains("\"createdAt\":\"");
  }

  @Test
  void neverPutsPostcodeOrAskerDataIntoTheRoom() {
    service.recommend(SESSION_ID, TOPIC_ID, null);

    var body = ArgumentCaptor.forClass(String.class);
    verify(systemMessages).postSystemMessage(eq(session), body.capture(), eq(true));
    assertThat(body.getValue())
        .doesNotContain("79098", "postcode", "asker", "asker@example.org")
        .contains("\"note\":null");
  }

  @Test
  void unknownSessionIsNotFound() {
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.recommend(SESSION_ID, TOPIC_ID, null))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void anotherConsultantIsForbidden() {
    when(authenticatedUser.getUserId()).thenReturn("someone-else");

    assertThatThrownBy(() -> service.recommend(SESSION_ID, TOPIC_ID, null))
        .isInstanceOf(ForbiddenException.class);
    verify(systemMessages, never()).postSystemMessage(any(), anyString(), anyBoolean());
  }

  @Test
  void unassignedEnquiryIsForbidden() {
    session.setConsultant(null);
    session.setStatus(SessionStatus.NEW);

    assertThatThrownBy(() -> service.recommend(SESSION_ID, TOPIC_ID, null))
        .isInstanceOf(ForbiddenException.class);
  }

  @ParameterizedTest
  @EnumSource(
      value = SessionStatus.class,
      names = {"IN_PROGRESS"},
      mode = EnumSource.Mode.EXCLUDE)
  void onlyAcceptedConversationsQualify(SessionStatus status) {
    session.setStatus(status);

    assertThatThrownBy(() -> service.recommend(SESSION_ID, TOPIC_ID, null))
        .isInstanceOf(ConflictException.class);
  }

  @ParameterizedTest
  @EnumSource(
      value = ConversationType.class,
      names = {"INTERNAL_GROUP", "SELF_HELP"})
  void groupConversationsDoNotQualify(ConversationType type) {
    session.setConversationType(type);

    assertThatThrownBy(() -> service.recommend(SESSION_ID, TOPIC_ID, null))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void membersOnlyChatSessionsDoNotQualify() {
    when(membersOnlyChatAccess.concerns(session)).thenReturn(true);

    assertThatThrownBy(() -> service.recommend(SESSION_ID, TOPIC_ID, null))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void unknownTopicIsABadRequest() {
    when(topicService.getTopicById(TOPIC_ID)).thenReturn(null);

    assertThatThrownBy(() -> service.recommend(SESSION_ID, TOPIC_ID, null))
        .isInstanceOf(BadRequestException.class);
    verify(systemMessages, never()).postSystemMessage(any(), anyString(), anyBoolean());
  }

  @Test
  void missingTopicIsABadRequest() {
    assertThatThrownBy(() -> service.recommend(SESSION_ID, null, null))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void noteLongerThan1000CharactersAfterTrimmingIsABadRequest() {
    assertThatThrownBy(() -> service.recommend(SESSION_ID, TOPIC_ID, "x".repeat(1001)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void noteOf1000CharactersPlusWhitespaceIsAccepted() {
    service.recommend(SESSION_ID, TOPIC_ID, "  " + "x".repeat(1000) + "  ");

    verify(systemMessages).postSystemMessage(eq(session), anyString(), eq(true));
  }

  @Test
  void blankNoteMeansNoNote() {
    assertThat(FurtherCounsellingRecommendationService.normalizeNote("   ")).isNull();
  }

  @Test
  void failedPostIsReported() {
    when(systemMessages.postSystemMessage(any(), anyString(), anyBoolean())).thenReturn(false);

    assertThatThrownBy(() -> service.recommend(SESSION_ID, TOPIC_ID, null))
        .isInstanceOf(InternalServerErrorException.class);
  }
}
