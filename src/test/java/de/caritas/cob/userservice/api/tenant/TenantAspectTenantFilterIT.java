package de.caritas.cob.userservice.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import jakarta.persistence.EntityManager;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=true"})
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional
class TenantAspectTenantFilterIT {

  @Autowired private AdminRepository adminRepository;

  @Autowired private EntityManager entityManager;

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  @Test
  void technicalContext_ShouldSeeAllTenants_AfterTenantScopedQueryOnSamePersistenceContext() {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    Admin tenantOneAdmin = admin("tenant-one-admin", 1L);
    Admin tenantEightyThreeAdmin = admin("tenant-eighty-three-admin", 83L);
    adminRepository.saveAll(Set.of(tenantOneAdmin, tenantEightyThreeAdmin));
    entityManager.flush();
    entityManager.clear();

    TenantContext.setCurrentTenant(1L);
    assertThat(
            adminRepository.findAllByIdIn(
                Set.of(tenantOneAdmin.getId(), tenantEightyThreeAdmin.getId())))
        .extracting(Admin::getTenantId)
        .containsExactly(1L);

    entityManager.clear();
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);

    assertThat(
            adminRepository.findAllByIdIn(
                Set.of(tenantOneAdmin.getId(), tenantEightyThreeAdmin.getId())))
        .extracting(Admin::getTenantId)
        .containsExactlyInAnyOrder(1L, 83L);
  }

  private Admin admin(String id, Long tenantId) {
    return Admin.builder()
        .id(id)
        .tenantId(tenantId)
        .username(id)
        .firstName("E2E")
        .lastName(id)
        .email(id + "@synthetic.oriso.test")
        .type(AdminType.TENANT)
        .build();
  }
}
