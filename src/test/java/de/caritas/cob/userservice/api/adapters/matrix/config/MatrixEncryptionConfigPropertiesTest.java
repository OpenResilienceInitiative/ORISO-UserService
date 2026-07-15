package de.caritas.cob.userservice.api.adapters.matrix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class MatrixEncryptionConfigPropertiesTest {

  private static final Path APPLICATION_PROPERTIES =
      Path.of("src/main/resources/application.properties");

  @Test
  void matrixEncryptionShouldBeDisabledUnlessExplicitlyEnabledByEnvironment() throws IOException {
    assertThat(new MatrixConfig().isEncryptionEnabled()).isFalse();

    var properties = new Properties();
    try (var input = Files.newInputStream(APPLICATION_PROPERTIES)) {
      properties.load(input);
    }

    assertThat(properties.getProperty("matrix.encryptionEnabled"))
        .isEqualTo("${MATRIX_ENCRYPTION_ENABLED:false}");
  }
}
