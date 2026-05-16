package de.caritas.cob.userservice.api.model;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.neovisionaries.i18n.LanguageCode;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ConsultantTest {

  private static final String FIRSTNAME = "firstname";
  private static final String LASTNAME = "lastname";
  private static final Consultant CONSULTANT =
      Consultant.builder()
          .id("yyy")
          .rocketChatId("XXX")
          .username("consultant")
          .firstName(FIRSTNAME)
          .lastName(LASTNAME)
          .email("consultant@domain.de")
          .absent(false)
          .teamConsultant(false)
          .languageFormal(false)
          .tenantId(1L)
          .encourage2fa(true)
          .magicLinkLoginEnabled(true)
          .notifyEnquiriesRepeating(true)
          .notifyNewChatMessageFromAdviceSeeker(true)
          .status(ConsultantStatus.CREATED)
          .walkThroughEnabled(false)
          .languageCode(LanguageCode.de)
          .notificationsEnabled(false)
          .build();

  @Test
  public void getFullName_Should_Return_FirstnameAndLastname() {
    String result = CONSULTANT.getFullName();
    String expectedFullName = FIRSTNAME + " " + LASTNAME;

    assertEquals(expectedFullName, result);
  }

  @Test
  public void equals_Should_returnTrue_When_objectIsSameReference() {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);

    assertThat(consultant, is(consultant));
  }

  @Test
  public void equals_Should_returnFalse_When_objectIsNoConsultantInstance() {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);

    boolean equals = consultant.equals(new Object());

    assertThat(equals, is(false));
  }

  @Test
  public void equals_Should_returnFalse_When_consultantIdsAreDifferent() {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    consultant.setId("1");
    Consultant otherConsultant = new EasyRandom().nextObject(Consultant.class);
    otherConsultant.setId("2");

    boolean equals = consultant.equals(otherConsultant);

    assertThat(equals, is(false));
  }

  @Test
  public void equals_Should_returnTrue_When_consultantIdsAreEqual() {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    consultant.setId("1");
    Consultant otherConsultant = new EasyRandom().nextObject(Consultant.class);
    otherConsultant.setId("1");

    boolean equals = consultant.equals(otherConsultant);

    assertThat(equals, is(true));
  }
}
