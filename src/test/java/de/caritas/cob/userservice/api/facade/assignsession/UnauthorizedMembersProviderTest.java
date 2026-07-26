package de.caritas.cob.userservice.api.facade.assignsession;

import static de.caritas.cob.userservice.api.testHelper.FieldConstants.FIELD_NAME_ROCKET_CHAT_SYSTEM_USER_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.RC_GROUP_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.ROCKET_CHAT_SYSTEM_USER_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.SESSION_WITH_ASKER_AND_CONSULTANT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.TEAM_SESSION_WITH_ASKER_AND_CONSULTANT;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.exception.MessageClientException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.SessionAssignmentChatGateway;
import de.caritas.cob.userservice.api.service.ConsultantService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jeasy.random.EasyRandom;
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
class UnauthorizedMembersProviderTest {

  @InjectMocks UnauthorizedMembersProvider unauthorizedMembersProvider;

  @Mock ConsultantService consultantService;

  @Mock SessionAssignmentChatGateway sessionAssignmentChatGateway;

  EasyRandom easyRandom = new EasyRandom();
  Consultant newConsultant = easyRandom.nextObject(Consultant.class);
  Consultant normalConsultant = easyRandom.nextObject(Consultant.class);
  Consultant teamConsultant = easyRandom.nextObject(Consultant.class);
  Consultant teamConsultant2 = easyRandom.nextObject(Consultant.class);
  Consultant mainConsultant = easyRandom.nextObject(Consultant.class);
  Consultant mainConsultant2 = easyRandom.nextObject(Consultant.class);
  List<String> initialMemberList;

  @BeforeEach
  void setup() throws SecurityException {
    setField(
        unauthorizedMembersProvider,
        FIELD_NAME_ROCKET_CHAT_SYSTEM_USER_ID,
        ROCKET_CHAT_SYSTEM_USER_ID);
    newConsultant.setRocketChatId("newConsultantRcId");
    normalConsultant.setRocketChatId("normalConsultantRcId");
    normalConsultant.setTeamConsultant(false);
    teamConsultant.setRocketChatId("teamConsultantRcId");
    teamConsultant.setTeamConsultant(true);
    teamConsultant2.setRocketChatId("teamConsultantRcId2");
    teamConsultant2.setTeamConsultant(true);
    mainConsultant.setRocketChatId("mainConsultantRcId");
    mainConsultant.setTeamConsultant(true);
    mainConsultant2.setRocketChatId("mainConsultantRcId2");
    mainConsultant2.setTeamConsultant(true);
    initialMemberList =
        asList(
            "userRcId",
            "newConsultantRcId",
            "normalConsultantRcId",
            "otherRcId",
            "otherRcId2",
            "teamConsultantRcId",
            "teamConsultantRcId2",
            "mainConsultantRcId",
            "mainConsultantRcId2",
            "rcTechnicalRcId",
            ROCKET_CHAT_SYSTEM_USER_ID,
            "techUserRcId");
    List.of(
            newConsultant,
            normalConsultant,
            teamConsultant,
            teamConsultant2,
            mainConsultant,
            mainConsultant2)
        .forEach(
            consultant ->
                when(consultantService.getConsultantByRcUserId(consultant.getRocketChatId()))
                    .thenReturn(Optional.of(consultant)));
  }

  @Test
  void obtainConsultantsToRemoveShouldNotIncludeConsultantToAssignIfNotAssignedAlready()
      throws MessageClientException {

    var consultant = easyRandom.nextObject(Consultant.class);
    when(sessionAssignmentChatGateway.technicalUserId()).thenReturn("techUserRcId");

    var consultantsToRemove =
        unauthorizedMembersProvider.obtainConsultantsToRemove(
            RC_GROUP_ID, SESSION_WITH_ASKER_AND_CONSULTANT, consultant, initialMemberList);

    consultantsToRemove.forEach(
        consultantToRemove -> {
          assertNotEquals(consultantToRemove.getId(), consultant.getId());
          assertNotEquals(consultantToRemove.getRocketChatId(), consultant.getRocketChatId());
        });
  }

  @Test
  void obtainConsultantsToRemoveShouldReturnEmptyWhenGroupHasNoMembers() {
    var consultant = easyRandom.nextObject(Consultant.class);

    var consultantsToRemove =
        unauthorizedMembersProvider.obtainConsultantsToRemove(
            RC_GROUP_ID, SESSION_WITH_ASKER_AND_CONSULTANT, consultant, List.of());

    assertThat(consultantsToRemove, is(empty()));
  }

  @Test
  void obtainConsultantsToRemoveShouldNotIncludeConsultantToAssignIfAlreadyAssigned()
      throws MessageClientException {

    var consultant = easyRandom.nextObject(Consultant.class);
    when(sessionAssignmentChatGateway.technicalUserId()).thenReturn("techUserRcId");
    var memberList = new ArrayList<>(initialMemberList);
    memberList.add(consultant.getRocketChatId());

    var consultantsToRemove =
        unauthorizedMembersProvider.obtainConsultantsToRemove(
            RC_GROUP_ID, SESSION_WITH_ASKER_AND_CONSULTANT, consultant, memberList);

    consultantsToRemove.forEach(
        consultantToRemove -> {
          assertNotEquals(consultantToRemove.getId(), consultant.getId());
          assertNotEquals(consultantToRemove.getRocketChatId(), consultant.getRocketChatId());
        });
  }

  @Test
  void obtainConsultantsToRemoveShouldNotIncludeConsultantToKeep() throws MessageClientException {

    var consultant = easyRandom.nextObject(Consultant.class);
    when(sessionAssignmentChatGateway.technicalUserId()).thenReturn("techUserRcId");
    var memberList = new ArrayList<>(initialMemberList);
    memberList.add(consultant.getRocketChatId());
    var consultantToKeep = easyRandom.nextObject(Consultant.class);
    memberList.add(consultantToKeep.getRocketChatId());

    var consultantsToRemove =
        unauthorizedMembersProvider.obtainConsultantsToRemove(
            RC_GROUP_ID,
            SESSION_WITH_ASKER_AND_CONSULTANT,
            consultant,
            memberList,
            consultantToKeep);

    consultantsToRemove.forEach(
        consultantToRemove -> {
          assertNotEquals(consultantToRemove.getId(), consultantToKeep.getId());
          assertNotEquals(consultantToRemove.getRocketChatId(), consultantToKeep.getRocketChatId());
        });
  }

  @Test
  void
      obtainConsultantsToRemove_Should_ReturnCorrectUnauthorizedMemberList_When_SessionIsNoTeamSession()
          throws MessageClientException {
    newConsultant.setTeamConsultant(false);
    when(sessionAssignmentChatGateway.technicalUserId()).thenReturn("techUserRcId");

    List<Consultant> result =
        unauthorizedMembersProvider.obtainConsultantsToRemove(
            RC_GROUP_ID, SESSION_WITH_ASKER_AND_CONSULTANT, newConsultant, initialMemberList);

    assertThat(result.size(), is(5));
    assertThat(
        result,
        contains(
            normalConsultant, teamConsultant, teamConsultant2, mainConsultant, mainConsultant2));
  }

  @Test
  void
      obtainConsultantsToRemove_Should_ReturnCorrectUnauthorizedMemberList_When_SessionIsNormalTeamSession()
          throws MessageClientException {
    newConsultant.setTeamConsultant(true);
    when(sessionAssignmentChatGateway.technicalUserId()).thenReturn("techUserRcId");
    when(consultantService.findConsultantsByAgencyId(
            TEAM_SESSION_WITH_ASKER_AND_CONSULTANT.getAgencyId()))
        .thenReturn(
            asList(
                newConsultant,
                normalConsultant,
                teamConsultant,
                teamConsultant2,
                mainConsultant,
                mainConsultant2));

    var result =
        unauthorizedMembersProvider.obtainConsultantsToRemove(
            RC_GROUP_ID, TEAM_SESSION_WITH_ASKER_AND_CONSULTANT, newConsultant, initialMemberList);

    assertThat(result.size(), is(1));
    assertThat(result, contains(normalConsultant));
  }
}
