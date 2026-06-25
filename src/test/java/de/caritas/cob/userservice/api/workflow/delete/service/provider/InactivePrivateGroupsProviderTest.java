package de.caritas.cob.userservice.api.workflow.delete.service.provider;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.RC_USER_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.RC_USER_ID_2;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupDTO;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatGetGroupsListAllException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import org.apache.commons.collections4.IterableUtils;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class InactivePrivateGroupsProviderTest {

  @InjectMocks private InactivePrivateGroupsProvider inactivePrivateGroupsProvider;

  @Mock private RocketChatService rocketChatService;
  @Mock private ChatRepository chatRepository;

  private LogbackCaptor logCaptor;

  @BeforeEach
  public void setup() {
    logCaptor = LogbackCaptor.forClass(LogService.class);
  }

  @AfterEach
  public void tearDown() {
    logCaptor.detach();
  }

  @Test
  public void
      retrieveUserWithInactiveGroupsMap_ShouldReturnEmptyMap_WhenFetchOfInactiveGroupsFails()
          throws RocketChatGetGroupsListAllException {

    when(chatRepository.findAll()).thenReturn(IterableUtils.emptyIterable());
    doThrow(new RocketChatGetGroupsListAllException(new RuntimeException()))
        .when(this.rocketChatService)
        .fetchAllInactivePrivateGroupsSinceGivenDate(any());

    var result = inactivePrivateGroupsProvider.retrieveUserWithInactiveGroupsMap();

    assertThat(result.isEmpty(), is(true));
  }

  @Test
  public void
      retrieveUserWithInactiveGroupsMap_Should_FetchInactiveRocketChatGroupsWithCorrectDate()
          throws RocketChatGetGroupsListAllException {

    String fieldNameSessionInactiveDeleteWorkflowCheckDays =
        "sessionInactiveDeleteWorkflowCheckDays";
    int valueSessionInactiveDeleteWorkflowCheckDays = 30;

    setField(
        inactivePrivateGroupsProvider,
        fieldNameSessionInactiveDeleteWorkflowCheckDays,
        valueSessionInactiveDeleteWorkflowCheckDays);
    LocalDateTime dateToCheck =
        LocalDateTime.now()
            .with(LocalTime.MIDNIGHT)
            .minusDays(valueSessionInactiveDeleteWorkflowCheckDays);
    when(chatRepository.findAll()).thenReturn(IterableUtils.emptyIterable());

    inactivePrivateGroupsProvider.retrieveUserWithInactiveGroupsMap();

    verify(rocketChatService, times(1)).fetchAllInactivePrivateGroupsSinceGivenDate(dateToCheck);
  }

  @Test
  public void retrieveUserWithInactiveGroupsMap_ShouldLogError_WhenFetchOfInactiveGroupsFails()
      throws RocketChatGetGroupsListAllException {

    when(chatRepository.findAll()).thenReturn(IterableUtils.emptyIterable());
    doThrow(new RocketChatGetGroupsListAllException(new RuntimeException()))
        .when(this.rocketChatService)
        .fetchAllInactivePrivateGroupsSinceGivenDate(any());

    inactivePrivateGroupsProvider.retrieveUserWithInactiveGroupsMap();

    org.assertj.core.api.Assertions.assertThat(logCaptor.count(Level.ERROR)).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(
            logCaptor.contains(Level.ERROR, "Rocket.Chat Error:"))
        .isTrue();
  }

  @Test
  public void retrieveUserWithInactiveGroupsMap_Should_ReturnUserWithInactiveGroupsMap()
      throws RocketChatGetGroupsListAllException {

    EasyRandom easyRandom = new EasyRandom();
    GroupDTO groupDTO1User1 = easyRandom.nextObject(GroupDTO.class);
    groupDTO1User1.getUser().setId(RC_USER_ID);
    GroupDTO groupDTO2User1 = easyRandom.nextObject(GroupDTO.class);
    groupDTO2User1.getUser().setId(RC_USER_ID);
    GroupDTO groupDTO1User2 = easyRandom.nextObject(GroupDTO.class);
    groupDTO1User2.getUser().setId(RC_USER_ID_2);
    List<GroupDTO> groupDtoResponseList = asList(groupDTO1User1, groupDTO2User1, groupDTO1User2);
    when(this.rocketChatService.fetchAllInactivePrivateGroupsSinceGivenDate(any()))
        .thenReturn(groupDtoResponseList);
    when(chatRepository.findAll()).thenReturn(IterableUtils.emptyIterable());

    var result = inactivePrivateGroupsProvider.retrieveUserWithInactiveGroupsMap();

    assertThat(result.size(), is(2));
    assertThat(result.containsKey(RC_USER_ID), is(true));
    assertThat(result.containsKey(RC_USER_ID_2), is(true));
    assertThat(result.get(RC_USER_ID).size(), is(2));
    assertThat(
        result.get(RC_USER_ID).stream().anyMatch(s -> groupDTO1User1.getId().equals(s)), is(true));
    assertThat(
        result.get(RC_USER_ID).stream().anyMatch(s -> groupDTO2User1.getId().equals(s)), is(true));
    assertThat(result.get(RC_USER_ID_2).size(), is(1));
    assertThat(result.get(RC_USER_ID_2).get(0).equals(groupDTO1User2.getId()), is(true));
  }

  @Test
  public void
      retrieveUserWithInactiveGroupsMap_Should_ReturnUserWithInactiveGroupsMapWithoutGroupChats()
          throws RocketChatGetGroupsListAllException {

    EasyRandom easyRandom = new EasyRandom();
    GroupDTO groupDTO1User1 = easyRandom.nextObject(GroupDTO.class);
    groupDTO1User1.getUser().setId(RC_USER_ID);
    GroupDTO groupDTO2User1 = easyRandom.nextObject(GroupDTO.class);
    groupDTO2User1.getUser().setId(RC_USER_ID);
    GroupDTO groupDTO1User2 = easyRandom.nextObject(GroupDTO.class);
    groupDTO1User2.getUser().setId(RC_USER_ID_2);
    List<GroupDTO> groupDtoResponseList = asList(groupDTO1User1, groupDTO2User1, groupDTO1User2);
    when(this.rocketChatService.fetchAllInactivePrivateGroupsSinceGivenDate(any()))
        .thenReturn(groupDtoResponseList);
    Chat chat = easyRandom.nextObject(Chat.class);
    chat.setGroupId(groupDTO1User2.getId());
    when(chatRepository.findAll()).thenReturn(Collections.singletonList(chat));

    var result = inactivePrivateGroupsProvider.retrieveUserWithInactiveGroupsMap();

    assertThat(result.size(), is(1));
    assertThat(result.containsKey(RC_USER_ID), is(true));
    assertThat(result.containsKey(RC_USER_ID_2), is(false));
    assertThat(result.get(RC_USER_ID).size(), is(2));
    assertThat(
        result.get(RC_USER_ID).stream().anyMatch(s -> groupDTO1User1.getId().equals(s)), is(true));
    assertThat(
        result.get(RC_USER_ID).stream().anyMatch(s -> groupDTO2User1.getId().equals(s)), is(true));
  }
}
