package org.safecrop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Contexto HTTP completo, sem infraestrutura de persistência. O IT cobre a integração real.
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
@AutoConfigureMockMvc
@Import(FoundationTest.FailingController.class)
class FoundationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void statusReturnsTypedResponse() throws Exception {
        mvc.perform(get("/api/v1/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.application").value("SafeCrop API"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void healthIsAvailableWithoutExposingDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void administrativeEndpointsAreNotExposed() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }

    @Test
    void unexpectedErrorDoesNotExposeInternalDetails() throws Exception {
        mvc.perform(get("/test/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("Não foi possível concluir a requisição."))
                .andExpect(content().string(not(containsString("internal-test-marker"))))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void unsupportedMethodKeepsClientErrorStatus() throws Exception {
        mvc.perform(post("/api/v1/status"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @RestController
    static class FailingController {
        @GetMapping("/test/failure")
        public void fail() {
            throw new IllegalStateException("internal-test-marker");
        }
    }
}
