package de.caritas.cob.userservice.api.conversation.provider;

import static de.caritas.cob.userservice.api.conversation.model.ConversationListType.REGISTERED_ENQUIRY;
import static de.caritas.cob.userservice.api.model.Session.RegistrationType.REGISTERED;
import static java.util.Objects.nonNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hibernate.validator.internal.util.CollectionHelper.asSet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.util.Lists;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.conversation.model.ConversationListType;
import de.caritas.cob.userservice.api.conversation.model.PageableListRequest;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.SessionData;
import de.caritas.cob.userservice.api.model.SessionData.SessionDataType;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import javax.persistence.EntityManager;
import org.apache.commons.collections4.iterators.PeekingIterator;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.ANY)
public class RegisteredEnquiryConversationListProviderIT {

  @Autowired
  private RegisteredEnquiryConversationListProvider registeredEnquiryConversationListProvider;

  @Autowired private SessionRepository sessionRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private EntityManager entityManager;

  @MockitoBean private UserAccountService userAccountProvider;

  @BeforeEach
  public void setup() {
    Consultant consultant = mock(Consultant.class);
    ConsultantAgency consultantAgency = mock(ConsultantAgency.class);
    when(consultant.getId()).thenReturn("consultant-id");
    when(consultantAgency.getAgencyId()).thenReturn(1L);
    when(consultant.getConsultantAgencies()).thenReturn(asSet(consultantAgency));
    when(this.userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);
  }

  @AfterEach
  public void cleanDatabase() {
    this.sessionRepository.deleteAll();
  }

  @Test
  public void
      buildConversations_Should_returnExpectedResponseDTO_When_consultantHasRegisteredEnquiries() {
    saveRegisteredSessions(10);
    PageableListRequest request = PageableListRequest.builder().count(5).offset(0).build();

    ConsultantSessionListResponseDTO responseDTO =
        this.registeredEnquiryConversationListProvider.buildConversations(request);

    assertThat(responseDTO.getCount(), is(5));
    assertThat(responseDTO.getOffset(), is(0));
    assertThat(responseDTO.getTotal(), is(10));
    assertThat(responseDTO.getSessions(), hasSize(5));
  }

  @Test
  public void buildConversations_Should_returnExpectedElements_When_paginationParamsAreAtTheEnd() {
    saveRegisteredSessions(10);
    PageableListRequest request = PageableListRequest.builder().count(3).offset(9).build();

    ConsultantSessionListResponseDTO responseDTO =
        this.registeredEnquiryConversationListProvider.buildConversations(request);

    assertThat(responseDTO.getCount(), is(1));
    assertThat(responseDTO.getOffset(), is(9));
    assertThat(responseDTO.getTotal(), is(10));
    assertThat(responseDTO.getSessions(), hasSize(1));
  }

  @Test
  public void buildConversations_Should_returnElementsInExpectedOrder() {
    saveRegisteredSessions(100);
    PageableListRequest request = PageableListRequest.builder().count(100).offset(0).build();

    ConsultantSessionListResponseDTO responseDTO =
        this.registeredEnquiryConversationListProvider.buildConversations(request);

    PeekingIterator<ConsultantSessionResponseDTO> peeker =
        new PeekingIterator<>(responseDTO.getSessions().iterator());
    while (peeker.hasNext()) {
      ConsultantSessionResponseDTO current = peeker.next();
      ConsultantSessionResponseDTO next = peeker.peek();
      if (nonNull(next)) {
        assertThat(next.getLatestMessage(), greaterThanOrEqualTo(current.getLatestMessage()));
      }
    }
  }

  @Test
  public void
      buildConversations_Should_includeSessionData_When_registeredEnquiryWasReloadedDetached() {
    saveRegisteredSessionWithSessionData();
    entityManager.clear();
    PageableListRequest request = PageableListRequest.builder().count(1).offset(0).build();

    ConsultantSessionListResponseDTO responseDTO =
        this.registeredEnquiryConversationListProvider.buildConversations(request);

    Map<String, Object> sessionData = responseDTO.getSessions().get(0).getUser().getSessionData();
    assertThat(responseDTO.getCount(), is(1));
    assertThat(sessionData.get("age"), is("42"));
  }

  @Test
  public void providedType_Should_return_registeredEnquiry() {
    ConversationListType conversationListType =
        this.registeredEnquiryConversationListProvider.providedType();

    assertThat(conversationListType, is(REGISTERED_ENQUIRY));
  }

  private void saveRegisteredSessions(int amount) {
    var random = new Random();
    List<Session> sessions =
        new EasyRandom().objects(Session.class, amount + 4).collect(Collectors.toList());
    User user = this.userRepository.findAll().iterator().next();
    sessions.forEach(
        session -> {
          session.setRegistrationType(REGISTERED);
          session.setConsultant(null);
          session.setUser(user);
          session.setId(null);
          session.setSessionData(null);
          session.setPostcode("12345");
          session.setAgencyId(1L);
          session.setStatus(SessionStatus.NEW);
          session.setConsultingTypeId(random.nextInt(127));
          session.setMainTopicId(null);
          session.setSessionTopics(Lists.newArrayList());
        });
    sessions.get(0).setStatus(SessionStatus.INITIAL);
    sessions.get(1).setStatus(SessionStatus.IN_PROGRESS);
    sessions.get(2).setStatus(SessionStatus.DONE);
    sessions.get(3).setStatus(SessionStatus.IN_ARCHIVE);
    this.sessionRepository.saveAll(sessions);
  }

  private void saveRegisteredSessionWithSessionData() {
    User user = this.userRepository.findAll().iterator().next();
    Session session = new Session(user, 1, "12345", 1L, SessionStatus.NEW, false);
    session.setId(null);
    session.setLanguageCode(LanguageCode.de);
    session.setEnquiryMessageDate(LocalDateTime.now());
    session.setCreateDate(LocalDateTime.now());
    session.setUpdateDate(LocalDateTime.now());
    session.setMainTopicId(9L);
    session.setIsConsultantDirectlySet(false);
    session.setSessionTopics(Lists.newArrayList());
    SessionData age = new SessionData(session, SessionDataType.REGISTRATION, "age", "42");
    session.setSessionData(Arrays.asList(age));

    this.sessionRepository.save(session);
  }
}
