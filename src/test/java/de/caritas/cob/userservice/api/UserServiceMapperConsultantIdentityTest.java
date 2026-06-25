package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant.ConsultantBase;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceMapperConsultantIdentityTest {

  @InjectMocks private UserServiceMapper userServiceMapper;

  @Mock
  @SuppressWarnings("unused")
  private UsernameTranscoder usernameTranscoder;

  private ConsultantBase consultantBase(String id) {
    return new ConsultantBase() {
      @Override
      public String getId() {
        return id;
      }

      @Override
      public String getFirstName() {
        return "First";
      }

      @Override
      public String getLastName() {
        return "Last";
      }

      @Override
      public String getEmail() {
        return "consultant@example.com";
      }

      @Override
      public java.time.LocalDateTime getUpdateDate() {
        return null;
      }
    };
  }

  @Test
  void mapOfConsultantShouldSetHasOtherIdentityTrueWhenFlagIsTrue() {
    var map =
        userServiceMapper.mapOf(
            consultantBase("consultant-1"),
            null,
            Collections.emptyList(),
            Collections.emptyMap(),
            true);

    assertThat(map).containsEntry("hasOtherIdentity", true);
  }

  @Test
  void mapOfConsultantShouldSetHasOtherIdentityFalseWhenFlagIsFalse() {
    var map =
        userServiceMapper.mapOf(
            consultantBase("consultant-1"),
            null,
            Collections.emptyList(),
            Collections.emptyMap(),
            false);

    assertThat(map).containsEntry("hasOtherIdentity", false);
  }

  @Test
  void mapOfConsultantPagedShouldFlagOnlyConsultantsWhoseIdIsInOtherIdentitySet() {
    var firstBase = consultantBase("consultant-1");
    var secondBase = consultantBase("consultant-2");
    var consultantPage =
        new org.springframework.data.domain.PageImpl<>(List.of(firstBase, secondBase));

    var result =
        userServiceMapper.mapOf(
            consultantPage,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyMap(),
            java.util.Set.of("consultant-1"));

    @SuppressWarnings("unchecked")
    var consultants = (List<Map<String, Object>>) result.get("consultants");
    var first =
        consultants.stream()
            .filter(c -> "consultant-1".equals(c.get("id")))
            .findFirst()
            .orElseThrow();
    var second =
        consultants.stream()
            .filter(c -> "consultant-2".equals(c.get("id")))
            .findFirst()
            .orElseThrow();

    assertThat(first).containsEntry("hasOtherIdentity", true);
    assertThat(second).containsEntry("hasOtherIdentity", false);
  }
}
