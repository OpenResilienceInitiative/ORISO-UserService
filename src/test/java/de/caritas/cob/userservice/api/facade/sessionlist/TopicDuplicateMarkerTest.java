package de.caritas.cob.userservice.api.facade.sessionlist;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.adapters.web.dto.ConversationType;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionTopicDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionResponseDTO;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TopicDuplicateMarkerTest {

  private static SessionDTO session(long id, int status, String created, Long topicId) {
    var dto = new SessionDTO().id(id).status(status).createDate(created);
    if (topicId != null) {
      dto.topic(new SessionTopicDTO().id(topicId));
    }
    return dto;
  }

  private static List<UserSessionResponseDTO> list(SessionDTO... sessions) {
    var list = new ArrayList<UserSessionResponseDTO>();
    for (var s : sessions) {
      list.add(new UserSessionResponseDTO().session(s));
    }
    list.add(new UserSessionResponseDTO()); // a chat entry without session
    return list;
  }

  @Test
  void onlyTheNewerAcceptedSameTopicSessionIsMarked() {
    var older = session(1, 2, "2026-10-01T09:00:00Z", 3L);
    var newer = session(2, 2, "2026-10-20T09:00:00Z", 3L);
    var otherTopic = session(3, 2, "2026-10-21T09:00:00Z", 4L);

    TopicDuplicateMarker.mark(list(newer, older, otherTopic));

    assertThat(newer.getIsTopicDuplicate()).isTrue();
    assertThat(older.getIsTopicDuplicate()).isFalse();
    assertThat(otherTopic.getIsTopicDuplicate()).isFalse();
  }

  @Test
  void archivedOrUnacceptedSessionsDoNotCount() {
    var archived = session(1, 4, "2026-10-01T09:00:00Z", 3L);
    var enquiry = session(2, 1, "2026-10-02T09:00:00Z", 3L);
    var accepted = session(3, 2, "2026-10-20T09:00:00Z", 3L);

    TopicDuplicateMarker.mark(list(archived, enquiry, accepted));

    assertThat(accepted.getIsTopicDuplicate()).isFalse();
    assertThat(archived.getIsTopicDuplicate()).isNull();
  }

  @Test
  void sessionsWithoutTopicOrInGroupsAreNeverMarked() {
    var older = session(1, 2, "2026-10-01T09:00:00Z", null);
    var newer = session(2, 2, "2026-10-20T09:00:00Z", null);
    var groupOld = session(3, 2, "2026-10-01T09:00:00Z", 5L);
    groupOld.conversationType(ConversationType.SELF_HELP);
    var groupNew = session(4, 2, "2026-10-20T09:00:00Z", 5L);

    TopicDuplicateMarker.mark(list(older, newer, groupOld, groupNew));

    assertThat(newer.getIsTopicDuplicate()).isNull();
    assertThat(groupNew.getIsTopicDuplicate()).isFalse();
  }
}
