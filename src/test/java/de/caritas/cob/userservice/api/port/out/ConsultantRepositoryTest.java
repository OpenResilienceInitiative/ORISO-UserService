package de.caritas.cob.userservice.api.port.out;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.EntityGraph;

class ConsultantRepositoryTest {

  @Test
  void findByIdAndDeleteDateIsNullShouldFetchLoginProfileRelations() throws Exception {
    var method = ConsultantRepository.class.getMethod("findByIdAndDeleteDateIsNull", String.class);

    var entityGraph = method.getAnnotation(EntityGraph.class);

    assertNotNull(entityGraph);
    assertArrayEquals(
        new String[] {"consultantAgencies", "languages"}, entityGraph.attributePaths());
  }
}
