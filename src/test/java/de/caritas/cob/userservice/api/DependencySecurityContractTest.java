package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DependencySecurityContractTest {

  @Test
  void httpCoreMustStayOnTheFixedStableRelease() throws IOException {
    assertThat(Files.readString(Path.of("pom.xml")))
        .contains("<httpcore5.version>5.4.3</httpcore5.version>");
  }
}
