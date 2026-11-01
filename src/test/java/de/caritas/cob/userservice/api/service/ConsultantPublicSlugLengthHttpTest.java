package de.caritas.cob.userservice.api.service;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiResponseEntityExceptionHandler;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ReservedPublicSlugRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * ORISO-UserService#1543: a too-long link name must reach the caller as 400 with a message, not as
 * a 500 from the 128-character database column. The real slug service and the real exception
 * handler sit behind a stub endpoint; repositories are mocks, so nothing is persisted.
 */
class ConsultantPublicSlugLengthHttpTest {

  @RestController
  static class StubSlugController {
    private final ConsultantPublicSlugService service;

    StubSlugController(ConsultantPublicSlugService service) {
      this.service = service;
    }

    @PutMapping("/slug")
    void requestSlug(@RequestBody String slug) {
      var consultant =
          Consultant.builder()
              .id("65c1095e-b977-493a-a34f-064b729d1d6c")
              .matrixUserId("@member:matrix.example")
              .username("max.mustermann")
              .firstName("Max")
              .lastName("Mustermann")
              .email("max.mustermann@example.com")
              .build();
      service.requestSlug(consultant, slug);
    }
  }

  private MockMvc mvc;

  @BeforeEach
  void setup() {
    var service =
        new ConsultantPublicSlugService(
            mock(ConsultantRepository.class), mock(ReservedPublicSlugRepository.class));
    mvc =
        MockMvcBuilders.standaloneSetup(new StubSlugController(service))
            .setControllerAdvice(new ApiResponseEntityExceptionHandler())
            .build();
  }

  @ParameterizedTest
  @ValueSource(ints = {51, 129, 300})
  void tooLongLinkNameIsABadRequest(int length) throws Exception {
    mvc.perform(put("/slug").content("a".repeat(length))).andExpect(status().isBadRequest());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 50})
  void linkNameUpToTheMaximumIsAccepted(int length) throws Exception {
    mvc.perform(put("/slug").content("a".repeat(length))).andExpect(status().isOk());
  }
}
