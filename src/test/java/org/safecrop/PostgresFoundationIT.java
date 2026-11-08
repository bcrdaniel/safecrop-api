package org.safecrop;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
class PostgresFoundationIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("safecrop_test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Flyway flyway;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private MockMvc mvc;

    @Test
    void datasourceAndJpaUseIsolatedPostgres() {
        assertThat(jdbc.queryForObject("select current_database()", String.class))
                .isEqualTo("safecrop_test");
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(entityManagerFactory.getProperties().get("hibernate.hbm2ddl.auto"))
                .isEqualTo("validate");
        try (var entityManager = entityManagerFactory.createEntityManager()) {
            assertThat(entityManager.createNativeQuery("select current_database()")
                    .getSingleResult()).isEqualTo("safecrop_test");
        }
    }

    @Test
    void flywayInitializesHistoryWithoutBusinessTables() {
        assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();
        assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
        assertThat(flyway.getConfiguration().getIgnoreMigrationPatterns()).isEmpty();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().applied()).isEmpty();
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' "
                        + "and table_type = 'BASE TABLE'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select to_regclass('public.flyway_schema_history')::text",
                String.class)).isEqualTo("flyway_schema_history");
        // Uma segunda execução também deve ser segura e não aplicar scripts inexistentes.
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void httpAndDatabaseHealthAreAvailable() throws Exception {
        mvc.perform(get("/api/v1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }
}
