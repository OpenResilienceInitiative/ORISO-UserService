package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;

import de.caritas.cob.userservice.api.service.cache.SharedReadCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("testing")
class UserServiceApplicationIT {

  @Autowired private SharedReadCache sharedReadCache;

  @Test
  void contextLoadsWithProductionSharedReadCache() {
    assertThat(mockingDetails(sharedReadCache).isMock()).isFalse();
  }
}
