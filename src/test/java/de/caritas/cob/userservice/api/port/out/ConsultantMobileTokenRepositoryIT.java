package de.caritas.cob.userservice.api.port.out;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantMobileToken;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.ANY)
class ConsultantMobileTokenRepositoryIT {

  @Autowired private ConsultantMobileTokenRepository underTest;

  @Autowired private ConsultantRepository consultantRepository;

  private Consultant consultant;

  private ConsultantMobileToken token;

  @AfterEach
  public void restore() {
    underTest.deleteAll();
    consultant = null;
    token = null;
  }

  @Test
  void saveShouldSaveToken() {
    givenAConsultant();
    givenAValidToken();

    var persistedToken = underTest.save(token);

    var optionalToken = underTest.findById(persistedToken.getId());
    assertTrue(optionalToken.isPresent());
    var foundToken = optionalToken.get();
    assertEquals(token.getMobileAppToken(), foundToken.getMobileAppToken());
    assertEquals(token.getConsultant(), foundToken.getConsultant());
  }

  private void givenAValidToken() {
    token = new ConsultantMobileToken();
    token.setConsultant(consultant);
    token.setMobileAppToken(RandomStringUtils.randomAlphanumeric(1024, 2048));
  }

  private void givenAConsultant() {
    consultant = consultantRepository.findAll().iterator().next();
  }
}
