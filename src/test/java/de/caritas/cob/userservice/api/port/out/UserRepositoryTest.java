package de.caritas.cob.userservice.api.port.out;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.EntityGraph;

class UserRepositoryTest {

  @Test
  void findByUserIdAndDeleteDateIsNullShouldFetchAgencyAssignments() throws Exception {
    var method = UserRepository.class.getMethod("findByUserIdAndDeleteDateIsNull", String.class);

    var entityGraph = method.getAnnotation(EntityGraph.class);

    assertNotNull(entityGraph);
    assertArrayEquals(new String[] {"userAgencies"}, entityGraph.attributePaths());
  }
}
