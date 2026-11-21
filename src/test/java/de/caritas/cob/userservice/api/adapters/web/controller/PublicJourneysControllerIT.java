package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.service.journey.PublicJourneyService;
import de.caritas.cob.userservice.api.service.journey.PublicJourneyService.PublicJourney;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The journey an advice seeker runs is read before registering, so without a login (#2333). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class PublicJourneysControllerIT {

  private static final String BASE = "/service/users/journeys";

  @Autowired private MockMvc mvc;
  @MockitoBean private PublicJourneyService service;

  @Test
  void anyoneReadsTheJourneyOfARegistrationTopic() throws Exception {
    when(service.forRegistration(1, null))
        .thenReturn(Optional.of(new PublicJourney("j1", Map.of("id", "j1"), null)));
    mvc.perform(get(BASE + "/registration").param("topicId", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.journeyId").value("j1"))
        .andExpect(header().string("Cache-Control", "no-cache"));
  }

  @Test
  void noStoredJourneyIsAnEmptyAnswerSoTheFormOfTodayStays() throws Exception {
    when(service.forRegistration(anyLong(), any())).thenReturn(Optional.empty());
    mvc.perform(get(BASE + "/registration").param("topicId", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.journey").doesNotExist());
  }

  @Test
  void anUnknownCentreIsNotFound() throws Exception {
    when(service.forRegistration(1, 9L)).thenThrow(new NoSuchElementException("no agency"));
    mvc.perform(get(BASE + "/registration").param("topicId", "1").param("agencyId", "9"))
        .andExpect(status().isNotFound());
  }

  @Test
  void anyoneReadsTheJourneyBehindALink() throws Exception {
    when(service.forLink(anyString(), any()))
        .thenReturn(
            Optional.of(new PublicJourney("j2", Map.of("id", "j2"), Map.of("slug", "schulden"))));
    mvc.perform(get(BASE + "/links/schulden"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.link.slug").value("schulden"));
  }

  @Test
  void writingIsNotOpen() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                BASE + "/links/schulden"))
        .andExpect(status().is4xxClientError());
  }
}
