package tenantstamping;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.TenantAware;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.Properties;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantStampingPersistenceTest {
  @AfterEach
  void clearContext() {
    TenantContext.clear();
  }

  @Test
  void applicationConfigurationPersistsTheCurrentTenantOnAnUnstampedEntity() throws Exception {
    assertPersistedTenant(2L, null, 2L);
  }

  @Test
  void preservesAnExplicitTenant() throws Exception {
    assertPersistedTenant(2L, 7L, 7L);
  }

  @Test
  void technicalContextDoesNotInventAnOwner() throws Exception {
    assertPersistedTenant(0L, null, null);
  }

  private void assertPersistedTenant(Long context, Long initial, Long expected) throws Exception {
    var properties = new Properties();
    try (var stream = getClass().getResourceAsStream("/application.properties")) {
      properties.load(stream);
    }
    var builder = new StandardServiceRegistryBuilder();
    properties.forEach(
        (key, value) -> {
          String name = key.toString();
          if (name.startsWith("spring.jpa.properties.hibernate.") && name.contains("interceptor")) {
            builder.applySetting(name.substring("spring.jpa.properties.".length()), value);
          }
        });
    var registry =
        builder
            .applySetting(
                "hibernate.connection.url", "jdbc:h2:mem:tenant-stamping;DB_CLOSE_DELAY=-1")
            .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
            .applySetting("hibernate.hbm2ddl.auto", "create-drop")
            .build();
    try (var factory =
        new MetadataSources(registry)
            .addAnnotatedClass(OwnedRecord.class)
            .buildMetadata()
            .buildSessionFactory()) {
      TenantContext.setCurrentTenant(context);
      try (var session = factory.openSession()) {
        var transaction = session.beginTransaction();
        var record = new OwnedRecord();
        record.id = 1L;
        record.tenantId = initial;
        session.persist(record);
        transaction.commit();
      }
      try (var session = factory.openSession()) {
        assertThat(session.find(OwnedRecord.class, 1L).getTenantId()).isEqualTo(expected);
      }
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
  }

  @Entity(name = "TenantStampingRecord")
  public static class OwnedRecord implements TenantAware {
    @Id private Long id;
    private Long tenantId;

    public Long getTenantId() {
      return tenantId;
    }

    public void setTenantId(Long tenantId) {
      this.tenantId = tenantId;
    }
  }
}
