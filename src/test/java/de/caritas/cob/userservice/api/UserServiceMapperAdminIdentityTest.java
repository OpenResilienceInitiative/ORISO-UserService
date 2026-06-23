package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminBase;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceMapperAdminIdentityTest {

  @InjectMocks private UserServiceMapper userServiceMapper;

  @Mock
  @SuppressWarnings("unused")
  private UsernameTranscoder usernameTranscoder;

  private AdminBase adminBase(String id) {
    return new AdminBase() {
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
        return "admin@example.com";
      }

      @Override
      public Long getTenantId() {
        return 1L;
      }

      @Override
      public Admin.AdminType getType() {
        return Admin.AdminType.AGENCY;
      }

      @Override
      public java.time.LocalDateTime getUpdateDate() {
        return null;
      }
    };
  }

  private Admin fullAdmin(String id) {
    return Admin.builder()
        .id(id)
        .username("admin-username")
        .firstName("First")
        .lastName("Last")
        .email("admin@example.com")
        .type(Admin.AdminType.AGENCY)
        .tenantId(1L)
        .build();
  }

  @Test
  void mapOfAdminShouldSetHasOtherIdentityTrueWhenFlagIsTrue() {
    var map =
        userServiceMapper.mapOfAdmin(
            adminBase("admin-1"),
            fullAdmin("admin-1"),
            Collections.emptyList(),
            Collections.emptyMap(),
            true);

    assertThat(map).containsEntry("hasOtherIdentity", true);
  }

  @Test
  void mapOfAdminShouldSetHasOtherIdentityFalseWhenFlagIsFalse() {
    var map =
        userServiceMapper.mapOfAdmin(
            adminBase("admin-1"),
            fullAdmin("admin-1"),
            Collections.emptyList(),
            Collections.emptyMap(),
            false);

    assertThat(map).containsEntry("hasOtherIdentity", false);
  }

  @Test
  void mapOfAdminPagedShouldFlagOnlyAdminsWhoseIdIsInOtherIdentitySet() {
    var firstBase = adminBase("admin-1");
    var secondBase = adminBase("admin-2");
    var adminsPage = new org.springframework.data.domain.PageImpl<>(List.of(firstBase, secondBase));

    var fullAdmins = List.of(fullAdmin("admin-1"), fullAdmin("admin-2"));

    var result =
        userServiceMapper.mapOfAdmin(
            adminsPage,
            fullAdmins,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyMap(),
            java.util.Set.of("admin-1"));

    @SuppressWarnings("unchecked")
    var admins = (List<Map<String, Object>>) result.get("admins");
    var first =
        admins.stream().filter(a -> "admin-1".equals(a.get("id"))).findFirst().orElseThrow();
    var second =
        admins.stream().filter(a -> "admin-2".equals(a.get("id"))).findFirst().orElseThrow();

    assertThat(first).containsEntry("hasOtherIdentity", true);
    assertThat(second).containsEntry("hasOtherIdentity", false);
  }
}
