package de.caritas.cob.userservice.api.workflow.delete.service.provider;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.RC_USER_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.RC_USER_ID_2;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.powermock.reflect.Whitebox.setInternalState;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupDTO;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatGetGroupsListAllException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.api.workflow.delete.model.InactiveGroup;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.commons.collections4.IterableUtils;
import org.jeasy.random.EasyRandom;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.slf4j.Logger;

@RunWith(MockitoJUnitRunner.class)
public class InactivePrivateGroupsProviderTest {

  @InjectMocks private InactivePrivateGroupsProvider inactivePrivateGroupsProvider;

  @Mock private RocketChatService rocketChatService;
  @Mock private ChatRepository chatRepository;
  @Mock private Logger logger;

  @Before
  public void setup() {
    setInternalState(LogService.class, "LOGGER", logger);
  }

  @Test
  public void
      retrieveUserWithInactiveGroupInfoMap_Should_ReturnEmptyMap_WhenFetchOfInactiveGroupsFails()
          throws RocketChatGetGroupsListAllException {
    // given
    when(chatRepository.findAll()).thenReturn(IterableUtils.emptyIterable());
    doThrow(new RocketChatGetGroupsListAllException(new RuntimeException()))
        .when(this.rocketChatService)
        .fetchAllInactivePrivateGroupsSinceGivenDate(any());

    // when
    Map<String, List<InactiveGroup>> result =
        inactivePrivateGroupsProvider.retrieveUserWithInactiveGroupInfoMap();

    // then
    assertThat(result.isEmpty(), is(true));
  }

  @Test
  public void
      retrieveUserWithInactiveGroupInfoMap_Should_FetchInactiveRocketChatGroupsWithCorrectDate()
          throws RocketChatGetGroupsListAllException {
    // given
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

    // when
    inactivePrivateGroupsProvider.retrieveUserWithInactiveGroupInfoMap();

    // then
    verify(rocketChatService, times(1)).fetchAllInactivePrivateGroupsSinceGivenDate(dateToCheck);
  }

  @Test
  public void retrieveUserWithInactiveGroupInfoMap_Should_LogError_WhenFetchOfInactiveGroupsFails()
      throws RocketChatGetGroupsListAllException {
    // given
    when(chatRepository.findAll()).thenReturn(IterableUtils.emptyIterable());
    doThrow(new RocketChatGetGroupsListAllException(new RuntimeException()))
        .when(this.rocketChatService)
        .fetchAllInactivePrivateGroupsSinceGivenDate(any());

    // when
    inactivePrivateGroupsProvider.retrieveUserWithInactiveGroupInfoMap();

    // then
    verify(this.logger, times(1)).error(anyString(), anyString(), anyString());
  }

  @Test
  public void retrieveUserWithInactiveGroupInfoMap_Should_ReturnGroupInfoWithLastMessageDate()
      throws RocketChatGetGroupsListAllException {
    // given
    EasyRandom easyRandom = new EasyRandom();
    GroupDTO groupDTO1User1 = easyRandom.nextObject(GroupDTO.class);
    groupDTO1User1.getUser().setId(RC_USER_ID);
    Date lastMessageDate = new Date();
    groupDTO1User1.setLastMessageDate(lastMessageDate);

    List<GroupDTO> groupDtoResponseList = asList(groupDTO1User1);
    when(this.rocketChatService.fetchAllInactivePrivateGroupsSinceGivenDate(any()))
        .thenReturn(groupDtoResponseList);
    when(chatRepository.findAll()).thenReturn(IterableUtils.emptyIterable());

    // when
    Map<String, List<InactiveGroup>> result =
        inactivePrivateGroupsProvider.retrieveUserWithInactiveGroupInfoMap();

    // then
    assertThat(result.size(), is(1));
    assertThat(result.containsKey(RC_USER_ID), is(true));
    List<InactiveGroup> groupInfoList = result.get(RC_USER_ID);
    assertThat(groupInfoList.size(), is(1));
    InactiveGroup groupInfo = groupInfoList.get(0);
    assertThat(groupInfo.getGroupId(), is(groupDTO1User1.getId()));
    assertThat(groupInfo.getLastMessageDate(), is(lastMessageDate));
  }

  @Test
  public void
      retrieveUserWithInactiveGroupInfoMap_Should_ReturnGroupInfoWithIdAndLastMessageDate_ForMultipleGroups()
          throws RocketChatGetGroupsListAllException {
    // given
    EasyRandom easyRandom = new EasyRandom();
    GroupDTO groupDTO1User1 = easyRandom.nextObject(GroupDTO.class);
    groupDTO1User1.getUser().setId(RC_USER_ID);
    Date lastMessageDate1 = new Date(System.currentTimeMillis() - 86400000); // yesterday
    groupDTO1User1.setLastMessageDate(lastMessageDate1);

    GroupDTO groupDTO2User1 = easyRandom.nextObject(GroupDTO.class);
    groupDTO2User1.getUser().setId(RC_USER_ID);
    Date lastMessageDate2 = new Date(System.currentTimeMillis() - 172800000); // 2 days ago
    groupDTO2User1.setLastMessageDate(lastMessageDate2);

    List<GroupDTO> groupDtoResponseList = asList(groupDTO1User1, groupDTO2User1);
    when(this.rocketChatService.fetchAllInactivePrivateGroupsSinceGivenDate(any()))
        .thenReturn(groupDtoResponseList);
    when(chatRepository.findAll()).thenReturn(IterableUtils.emptyIterable());

    // when
    Map<String, List<InactiveGroup>> result =
        inactivePrivateGroupsProvider.retrieveUserWithInactiveGroupInfoMap();

    // then
    assertThat(result.size(), is(1));
    assertThat(result.containsKey(RC_USER_ID), is(true));
    List<InactiveGroup> groupInfoList = result.get(RC_USER_ID);
    assertThat(groupInfoList.size(), is(2));

    // Verify first group info
    InactiveGroup groupInfo1 =
        groupInfoList.stream()
            .filter(g -> g.getGroupId().equals(groupDTO1User1.getId()))
            .findFirst()
            .orElse(null);
    assertThat(groupInfo1, is(notNullValue()));
    assertThat(groupInfo1.getLastMessageDate(), is(lastMessageDate1));

    // Verify second group info
    InactiveGroup groupInfo2 =
        groupInfoList.stream()
            .filter(g -> g.getGroupId().equals(groupDTO2User1.getId()))
            .findFirst()
            .orElse(null);
    assertThat(groupInfo2, is(notNullValue()));
    assertThat(groupInfo2.getLastMessageDate(), is(lastMessageDate2));
  }

  @Test
  public void
      retrieveUserWithInactiveGroupInfoMap_Should_ReturnUserWithInactiveGroupInfoMap_ForMultipleUsers()
          throws RocketChatGetGroupsListAllException {
    // given
    EasyRandom easyRandom = new EasyRandom();
    GroupDTO groupDTO1User1 = easyRandom.nextObject(GroupDTO.class);
    groupDTO1User1.getUser().setId(RC_USER_ID);
    Date lastMessageDate1 = new Date();
    groupDTO1User1.setLastMessageDate(lastMessageDate1);

    GroupDTO groupDTO2User1 = easyRandom.nextObject(GroupDTO.class);
    groupDTO2User1.getUser().setId(RC_USER_ID);
    Date lastMessageDate2 = new Date();
    groupDTO2User1.setLastMessageDate(lastMessageDate2);

    GroupDTO groupDTO1User2 = easyRandom.nextObject(GroupDTO.class);
    groupDTO1User2.getUser().setId(RC_USER_ID_2);
    Date lastMessageDate3 = new Date();
    groupDTO1User2.setLastMessageDate(lastMessageDate3);

    List<GroupDTO> groupDtoResponseList = asList(groupDTO1User1, groupDTO2User1, groupDTO1User2);
    when(this.rocketChatService.fetchAllInactivePrivateGroupsSinceGivenDate(any()))
        .thenReturn(groupDtoResponseList);
    when(chatRepository.findAll()).thenReturn(IterableUtils.emptyIterable());

    // when
    Map<String, List<InactiveGroup>> result =
        inactivePrivateGroupsProvider.retrieveUserWithInactiveGroupInfoMap();

    // then
    assertThat(result.size(), is(2));
    assertThat(result.containsKey(RC_USER_ID), is(true));
    assertThat(result.containsKey(RC_USER_ID_2), is(true));
    assertThat(result.get(RC_USER_ID).size(), is(2));
    assertThat(result.get(RC_USER_ID_2).size(), is(1));

    // Verify user 1 groups
    assertThat(
        result.get(RC_USER_ID).stream()
            .anyMatch(g -> g.getGroupId().equals(groupDTO1User1.getId())),
        is(true));
    assertThat(
        result.get(RC_USER_ID).stream()
            .anyMatch(g -> g.getGroupId().equals(groupDTO2User1.getId())),
        is(true));

    // Verify user 2 groups
    assertThat(result.get(RC_USER_ID_2).get(0).getGroupId(), is(groupDTO1User2.getId()));
    assertThat(result.get(RC_USER_ID_2).get(0).getLastMessageDate(), is(lastMessageDate3));
  }

  @Test
  public void retrieveUserWithInactiveGroupInfoMap_Should_ExcludeGroupChats()
      throws RocketChatGetGroupsListAllException {
    // given
    EasyRandom easyRandom = new EasyRandom();
    GroupDTO groupDTO1User1 = easyRandom.nextObject(GroupDTO.class);
    groupDTO1User1.getUser().setId(RC_USER_ID);
    groupDTO1User1.setLastMessageDate(new Date());

    GroupDTO groupDTO2User1 = easyRandom.nextObject(GroupDTO.class);
    groupDTO2User1.getUser().setId(RC_USER_ID);
    groupDTO2User1.setLastMessageDate(new Date());

    GroupDTO groupDTO1User2 = easyRandom.nextObject(GroupDTO.class);
    groupDTO1User2.getUser().setId(RC_USER_ID_2);
    groupDTO1User2.setLastMessageDate(new Date());

    List<GroupDTO> groupDtoResponseList = asList(groupDTO1User1, groupDTO2User1, groupDTO1User2);
    when(this.rocketChatService.fetchAllInactivePrivateGroupsSinceGivenDate(any()))
        .thenReturn(groupDtoResponseList);

    // Create a chat that matches one of the groups (should be filtered out)
    Chat chat = easyRandom.nextObject(Chat.class);
    chat.setGroupId(groupDTO1User2.getId());
    when(chatRepository.findAll()).thenReturn(Collections.singletonList(chat));

    // when
    Map<String, List<InactiveGroup>> result =
        inactivePrivateGroupsProvider.retrieveUserWithInactiveGroupInfoMap();

    // then
    assertThat(result.size(), is(1));
    assertThat(result.containsKey(RC_USER_ID), is(true));
    assertThat(result.containsKey(RC_USER_ID_2), is(false));
    assertThat(result.get(RC_USER_ID).size(), is(2));
    assertThat(
        result.get(RC_USER_ID).stream()
            .anyMatch(g -> g.getGroupId().equals(groupDTO1User1.getId())),
        is(true));
    assertThat(
        result.get(RC_USER_ID).stream()
            .anyMatch(g -> g.getGroupId().equals(groupDTO2User1.getId())),
        is(true));
  }

  @Test
  public void retrieveUserWithInactiveGroupInfoMap_Should_ReturnEmptyMap_WhenNoInactiveGroupsExist()
      throws RocketChatGetGroupsListAllException {
    // given
    when(this.rocketChatService.fetchAllInactivePrivateGroupsSinceGivenDate(any()))
        .thenReturn(Collections.emptyList());
    when(chatRepository.findAll()).thenReturn(IterableUtils.emptyIterable());

    // when
    Map<String, List<InactiveGroup>> result =
        inactivePrivateGroupsProvider.retrieveUserWithInactiveGroupInfoMap();

    // then
    assertThat(result.isEmpty(), is(true));
  }

  @Test
  public void retrieveUserWithInactiveGroupInfoMap_Should_HandleNullLastMessageDate()
      throws RocketChatGetGroupsListAllException {
    // given
    EasyRandom easyRandom = new EasyRandom();
    GroupDTO groupDTO = easyRandom.nextObject(GroupDTO.class);
    groupDTO.getUser().setId(RC_USER_ID);
    groupDTO.setLastMessageDate(null); // No last message date

    List<GroupDTO> groupDtoResponseList = asList(groupDTO);
    when(this.rocketChatService.fetchAllInactivePrivateGroupsSinceGivenDate(any()))
        .thenReturn(groupDtoResponseList);
    when(chatRepository.findAll()).thenReturn(IterableUtils.emptyIterable());

    // when
    Map<String, List<InactiveGroup>> result =
        inactivePrivateGroupsProvider.retrieveUserWithInactiveGroupInfoMap();

    // then
    assertThat(result.size(), is(1));
    assertThat(result.containsKey(RC_USER_ID), is(true));
    InactiveGroup groupInfo = result.get(RC_USER_ID).get(0);
    assertThat(groupInfo.getGroupId(), is(groupDTO.getId()));
    assertThat(groupInfo.getLastMessageDate(), is(nullValue()));
  }

  @Test
  public void retrieveUserWithInactiveGroupInfoMap_Should_ExcludeAllGroupsIfAllAreGroupChats()
      throws RocketChatGetGroupsListAllException {
    // given
    EasyRandom easyRandom = new EasyRandom();
    GroupDTO groupDTO1 = easyRandom.nextObject(GroupDTO.class);
    groupDTO1.getUser().setId(RC_USER_ID);
    groupDTO1.setLastMessageDate(new Date());

    GroupDTO groupDTO2 = easyRandom.nextObject(GroupDTO.class);
    groupDTO2.getUser().setId(RC_USER_ID_2);
    groupDTO2.setLastMessageDate(new Date());

    List<GroupDTO> groupDtoResponseList = asList(groupDTO1, groupDTO2);
    when(this.rocketChatService.fetchAllInactivePrivateGroupsSinceGivenDate(any()))
        .thenReturn(groupDtoResponseList);

    // Create chats that match all groups
    Chat chat1 = easyRandom.nextObject(Chat.class);
    chat1.setGroupId(groupDTO1.getId());
    Chat chat2 = easyRandom.nextObject(Chat.class);
    chat2.setGroupId(groupDTO2.getId());
    when(chatRepository.findAll()).thenReturn(asList(chat1, chat2));

    // when
    Map<String, List<InactiveGroup>> result =
        inactivePrivateGroupsProvider.retrieveUserWithInactiveGroupInfoMap();

    // then
    assertThat(result.isEmpty(), is(true));
  }

  @Test
  public void retrieveUserWithInactiveGroupInfoMap_Should_PreserveGroupIdCorrectly()
      throws RocketChatGetGroupsListAllException {
    // given
    EasyRandom easyRandom = new EasyRandom();
    String expectedGroupId = "specific-group-id-12345";
    GroupDTO groupDTO = easyRandom.nextObject(GroupDTO.class);
    groupDTO.getUser().setId(RC_USER_ID);
    groupDTO.setId(expectedGroupId);
    groupDTO.setLastMessageDate(new Date());

    List<GroupDTO> groupDtoResponseList = asList(groupDTO);
    when(this.rocketChatService.fetchAllInactivePrivateGroupsSinceGivenDate(any()))
        .thenReturn(groupDtoResponseList);
    when(chatRepository.findAll()).thenReturn(IterableUtils.emptyIterable());

    // when
    Map<String, List<InactiveGroup>> result =
        inactivePrivateGroupsProvider.retrieveUserWithInactiveGroupInfoMap();

    // then
    assertThat(result.get(RC_USER_ID).get(0).getGroupId(), is(expectedGroupId));
  }
}
