package de.caritas.cob.userservice.api.service.session;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.toIsoTime;
import static de.caritas.cob.userservice.api.model.Session.RegistrationType.ANONYMOUS;
import static de.caritas.cob.userservice.api.model.Session.RegistrationType.REGISTERED;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionConsultantForConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionConsultantForUserDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionResponseDTO;
import de.caritas.cob.userservice.api.config.AppConfig;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionData;
import de.caritas.cob.userservice.api.model.User;
import java.time.LocalDateTime;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;

class SessionMapperTest {

  @Test
  void convertToSessionDTOShouldProjectConversationType() {
    Session session = new EasyRandom().nextObject(Session.class);
    session.setConversationType(ConversationType.LIVE_CHAT);

    SessionDTO sessionDTO = new SessionMapper().convertToSessionDTO(session);

    assertThat(
        sessionDTO.getConversationType().get(),
        is(de.caritas.cob.userservice.api.adapters.web.dto.ConversationType.LIVE_CHAT));
  }

  @Test
  void convertToSessionDTOShouldSerializeConversationTypeAsApiEnum() throws Exception {
    Session session = new EasyRandom().nextObject(Session.class);
    session.setConversationType(ConversationType.LIVE_CHAT);

    SessionDTO sessionDTO = new SessionMapper().convertToSessionDTO(session);

    org.assertj.core.api.Assertions.assertThat(
            new AppConfig().objectMapper().writeValueAsString(sessionDTO))
        .contains("\"conversationType\":\"LIVE_CHAT\"")
        .doesNotContain("\"conversationType\":{\"present\":true}");
  }

  @Test
  void
      convertToSessionDTO_Should_returnSessionDTOWithRegistrationType_When_registrationTypeIsAnonymous() {
    Session session = new EasyRandom().nextObject(Session.class);
    session.setRegistrationType(ANONYMOUS);

    SessionDTO sessionDTO = new SessionMapper().convertToSessionDTO(session);

    assertThat(sessionDTO.getRegistrationType(), is("ANONYMOUS"));
  }

  @Test
  void
      convertToSessionDTO_Should_returnSessionDTOWithCreateDateInIsoFormat_When_registrationTypeIsAnonymous() {
    Session session = new EasyRandom().nextObject(Session.class);
    LocalDateTime createDate = new EasyRandom().nextObject(LocalDateTime.class);
    session.setCreateDate(createDate);

    SessionDTO sessionDTO = new SessionMapper().convertToSessionDTO(session);

    assertThat(sessionDTO.getCreateDate(), is(toIsoTime(createDate)));
  }

  @Test
  void
      convertToSessionDTO_Should_returnSessionDTOWithRegistrationType_When_registrationTypeIsRegistered() {
    Session session = new EasyRandom().nextObject(Session.class);
    session.setRegistrationType(REGISTERED);

    SessionDTO sessionDTO = new SessionMapper().convertToSessionDTO(session);

    assertThat(sessionDTO.getRegistrationType(), is("REGISTERED"));
  }

  @Test
  void convertToSessionDTO_Should_leaveConversationTypeAndAskerRcIdNull_When_userIsNull() {
    Session session = new EasyRandom().nextObject(Session.class);
    session.setConversationType(null);
    session.setUser(null);
    session.setRegistrationType(REGISTERED);

    SessionDTO sessionDTO = new SessionMapper().convertToSessionDTO(session);

    assertNull(sessionDTO.getAskerRcId());
  }

  @Test
  void convertToSessionDTO_Should_leaveAskerRcIdNull_When_userRcIdMissing() {
    Session session = new EasyRandom().nextObject(Session.class);
    session.setRegistrationType(REGISTERED);
    User user = new User();
    user.setMatrixUserId(null);
    session.setUser(user);

    SessionDTO sessionDTO = new SessionMapper().convertToSessionDTO(session);

    assertNull(sessionDTO.getAskerRcId());
  }

  @Test
  void toConsultantSessionDto_Should_populateUserConsultantAndLatestMessage() {
    Session session = new EasyRandom().nextObject(Session.class);
    session.setRegistrationType(REGISTERED);
    session.setEnquiryMessageDate(LocalDateTime.of(2024, 5, 1, 10, 0));

    ConsultantSessionResponseDTO dto = new SessionMapper().toConsultantSessionDto(session);

    assertThat(dto.getSession(), notNullValue());
    assertThat(dto.getUser(), notNullValue());
    assertThat(dto.getConsultant(), notNullValue());
    assertEquals(java.sql.Date.valueOf("2024-05-01"), dto.getLatestMessage());
  }

  @Test
  void toConsultantSessionDto_Should_returnNullLatestMessage_When_enquiryDateMissing() {
    Session session = new EasyRandom().nextObject(Session.class);
    session.setRegistrationType(REGISTERED);
    session.setEnquiryMessageDate(null);

    ConsultantSessionResponseDTO dto = new SessionMapper().toConsultantSessionDto(session);

    assertNull(dto.getLatestMessage());
  }

  @Test
  void toConsultantSessionDto_Should_returnNullUser_When_sessionUserIsNull() {
    Session session = new EasyRandom().nextObject(Session.class);
    session.setRegistrationType(REGISTERED);
    session.setUser(null);

    ConsultantSessionResponseDTO dto = new SessionMapper().toConsultantSessionDto(session);

    assertNull(dto.getUser());
  }

  @Test
  void toConsultantSessionDto_Should_returnNullConsultant_When_consultantMissing() {
    Session session = new EasyRandom().nextObject(Session.class);
    session.setRegistrationType(REGISTERED);
    session.setConsultant(null);

    ConsultantSessionResponseDTO dto = new SessionMapper().toConsultantSessionDto(session);

    assertNull(dto.getConsultant());
  }

  @Test
  void buildSessionDataMapFromSession_Should_includeOnlyRegisteredKeys() {
    Session session = new EasyRandom().nextObject(Session.class);
    SessionData knownKey = new SessionData();
    knownKey.setKey("age");
    knownKey.setValue("30");
    SessionData unknownKey = new SessionData();
    unknownKey.setKey("random-key");
    unknownKey.setValue("value");
    session.setSessionData(java.util.List.of(knownKey, unknownKey));

    var map = new SessionMapper().buildSessionDataMapFromSession(session);

    assertThat(map, hasEntry("age", "30"));
    assertEquals(1, map.size());
  }

  @Test
  void toConsultantSessionDto_Should_ProjectSessionScopedDisplayName() {
    Session session = new EasyRandom().nextObject(Session.class);
    session.setRegistrationType(ANONYMOUS);
    SessionData displayName = new SessionData();
    displayName.setKey("displayName");
    displayName.setValue("Behutsames Pferd Jules");
    session.setSessionData(java.util.List.of(displayName));

    ConsultantSessionResponseDTO dto = new SessionMapper().toConsultantSessionDto(session);

    assertEquals("Behutsames Pferd Jules", dto.getUser().getDisplayName());
    assertThat(dto.getUser().getSessionData(), hasEntry("displayName", "Behutsames Pferd Jules"));
  }

  @Test
  void
      toGroupSessionResponse_userVariant_Should_returnResponseWithoutConsultant_When_consultantNull() {
    UserSessionResponseDTO input = new UserSessionResponseDTO();
    input.setSession(new SessionDTO());
    input.setConsultant(null);

    var response = new SessionMapper().toGroupSessionResponse(input);

    assertThat(response.getConsultant(), nullValue());
    assertThat(response.getSession(), notNullValue());
  }

  @Test
  void toGroupSessionResponse_userVariant_Should_mapConsultant_When_present() {
    UserSessionResponseDTO input = new UserSessionResponseDTO();
    SessionConsultantForUserDTO consultant = new SessionConsultantForUserDTO();
    consultant.setConsultantId("c-1");
    consultant.setUsername("uname");
    consultant.setDisplayName("Alice");
    consultant.setAbsent(true);
    consultant.setAbsenceMessage("out");
    input.setConsultant(consultant);

    var response = new SessionMapper().toGroupSessionResponse(input);

    assertEquals("c-1", response.getConsultant().getId());
    assertEquals("uname", response.getConsultant().getUsername());
    assertEquals("Alice", response.getConsultant().getDisplayName());
    assertEquals(true, response.getConsultant().isAbsent());
    assertEquals("out", response.getConsultant().getAbsenceMessage());
  }

  @Test
  void
      toGroupSessionResponse_consultantVariant_Should_returnResponseWithoutConsultant_When_consultantNull() {
    ConsultantSessionResponseDTO input = new ConsultantSessionResponseDTO();
    input.setSession(new SessionDTO());
    input.setConsultant(null);

    var response = new SessionMapper().toGroupSessionResponse(input);

    assertThat(response.getConsultant(), nullValue());
  }

  @Test
  void toGroupSessionResponse_consultantVariant_Should_mapConsultant_When_present() {
    ConsultantSessionResponseDTO input = new ConsultantSessionResponseDTO();
    SessionConsultantForConsultantDTO consultant = new SessionConsultantForConsultantDTO();
    consultant.setId("c-9");
    consultant.setFirstName("Alice");
    consultant.setLastName("Smith");
    consultant.setDisplayName("Alice S.");
    input.setConsultant(consultant);

    var response = new SessionMapper().toGroupSessionResponse(input);

    assertEquals("c-9", response.getConsultant().getId());
    assertEquals("Alice", response.getConsultant().getFirstName());
    assertEquals("Smith", response.getConsultant().getLastName());
    assertEquals("Alice S.", response.getConsultant().getDisplayName());
  }
}
