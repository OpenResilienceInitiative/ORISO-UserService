package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.service.session.SessionTopicEnrichmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** #1536: the reason picker must only ever offer the four neutral reason codes. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class CaseHandoverReasonsEndpointIT {

  @MockitoBean SessionTopicEnrichmentService sessionTopicEnrichmentService;
  @Autowired private MockMvc mvc;

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void getReasonsServesTheFourNeutralCodesAndNoHealthStatement() throws Exception {
    mvc.perform(get("/users/case-handover/reasons").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$[*].code")
                .value(
                    contains(
                        "ADVICE_REQUESTED",
                        "PLANNED_ABSENCE",
                        "UNPLANNED_ABSENCE",
                        "ASSIGNMENT_ENDED")))
        .andExpect(content().string(not(containsStringIgnoringCase("COUNSELLOR_IS_ILL"))))
        .andExpect(content().string(not(containsStringIgnoringCase("erkrankt"))))
        .andExpect(content().string(not(containsStringIgnoringCase(" is ill"))));
  }
}
