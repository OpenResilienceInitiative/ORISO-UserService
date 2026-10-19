package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class GroupAppointmentRenderedTemplateContractTest {
  private static final Pattern UNRESOLVED = Pattern.compile("\\{\\{\\s*[\\w.]+\\s*}}");

  @Test
  void allFourEventsForBothRolesRenderInEverySupportedVariant() {
    var renderer = new OrisoEmailRenderer();
    Map<String, String> values = new HashMap<>();
    values.put("platformName", "ORISO");
    values.put("offeringName", "ORISO");
    values.put("operatorName", "Platform operator");
    values.put("orgName", "Group owner organisation");
    values.put("orgAddress", "");
    values.put("contactLine", "");
    values.put("logoUrl", "");
    values.put("primaryColor", "#a5000a");
    values.put("accentColor", "#cc1e1c");
    values.put("appUrl", "https://owner.example.org");
    values.put("settingsUrl", "https://owner.example.org/profile/einstellungen");
    values.put("privacyUrl", "https://owner.example.org/datenschutz");
    values.put("imprintUrl", "https://owner.example.org/impressum");
    values.put("unsubscribeUrl", "https://owner.example.org/profile/einstellungen/email");
    values.put("appointmentDate", "28.03.2027");
    values.put("appointmentTime", "09:00 MESZ");
    values.put("appointmentUrl", "https://owner.example.org/login?seriesId=42");

    for (var event : new String[] {"bestaetigt", "verschoben", "abgesagt", "erinnerung"}) {
      for (var role : new String[] {"teilnahme", "beratung"}) {
        for (var tone : OrisoEmailRenderer.Tone.values()) {
          var email = renderer.render("selbsthilfe-termin-" + event + "-" + role, tone, values);
          assertThat(email.subject()).isNotBlank();
          assertThat(email.html()).contains("https://owner.example.org/login?seriesId=42");
          assertThat(email.text()).contains("https://owner.example.org/login?seriesId=42");
          assertThat(UNRESOLVED.matcher(email.html()).find()).isFalse();
          assertThat(UNRESOLVED.matcher(email.text()).find()).isFalse();
          assertThat(UNRESOLVED.matcher(email.subject()).find()).isFalse();
        }
      }
    }
  }
}
