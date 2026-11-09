package org.safecrop;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.safecrop.company.Company;
import org.safecrop.company.CompanyRepository;
import org.safecrop.identity.AppUser;
import org.safecrop.identity.AppUserRepository;
import org.safecrop.identity.UserRole;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
class PostgresFoundationIT {
    @org.springframework.test.context.bean.override.convention.TestBean(name = "loginProtectionClock",
            methodName = "org.safecrop.auth.LoginTestClocks#oneMinutePerRead", enforceOverride = true)
    java.util.function.LongSupplier loginProtectionClock;

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("safecrop_test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> TestSecrets.JWT_SECRET);
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
    @Autowired
    private CompanyRepository companies;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private HealthContributorRegistry healthContributors;
    @PersistenceContext
    private EntityManager entityManager;
    @Autowired
    private org.safecrop.identity.AccountProvisioningService accounts;
    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired
    private org.springframework.context.ApplicationContext context;

    @Test
    void onlySharedPersistenceInfrastructureIsActive() {
        assertThat(context.getBeansOfType(javax.sql.DataSource.class)).hasSize(1);
        assertThat(context.getBeansOfType(EntityManagerFactory.class)).hasSize(1);
        assertThat(context.getBeansOfType(org.safecrop.operational.OperationalTransaction.class)).hasSize(1);
        assertThat(entityManagerFactory.getMetamodel().getEntities())
                .extracting(type -> type.getJavaType().getSimpleName())
                .contains("AppUser", "Company").doesNotContain("OperationalDatabase");
        assertThat(context.getBeanDefinitionNames()).doesNotContain("operationalConnections",
                "operationalCompanyResolver", "operationalProvisioningService", "postgresProvisioner",
                "operationalProvisioningController", "operationalDatabaseRepository");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABSENT", "PENDING", "PROVISIONING", "READY", "FAILED", "SUSPENDED"})
    @Transactional
    void accountCreationLoginAndMeDoNotDependOnLegacyDatabaseState(String state) throws Exception {
        var company = companies.saveAndFlush(new Company("Independent farm", "independent-farm"));
        var user = accounts.createCompanyAdministrator(company.getId(), "Manager", "independent@example.com", "test-only-independent-password");
        assertThat(jdbc.queryForObject("select count(*) from operational_databases where company_id=?", Integer.class, company.getId())).isZero();
        if (!"ABSENT".equals(state)) insertLocator(company.getId(), "safecrop_op_legacy", state);
        var before = jdbc.queryForList("select * from operational_databases where company_id=?", company.getId());
        entityManager.clear();
        String response = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content(json.writeValueAsString(java.util.Map.of("email", "independent@example.com", "password", "test-only-independent-password"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + json.readTree(response).get("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(user.id().toString()))
                .andExpect(jsonPath("$.company.id").value(company.getId().toString()));
        assertThat(jdbc.queryForList("select * from operational_databases where company_id=?", company.getId())).isEqualTo(before);
    }

    @Test
    void registryUniquenessAppliesEvenToSuspendedRegistrations() {
        rejected(db -> {
            UUID company = insertCompany("registry-a");
            insertLocator(company, "safecrop_op_a", "SUSPENDED");
            insertLocator(company, "safecrop_op_b", "PENDING");
        });
        rejected(db -> {
            insertLocator(insertCompany("registry-a"), "safecrop_op_same", "PENDING");
            insertLocator(insertCompany("registry-b"), "safecrop_op_same", "READY");
        });
    }

    @Test
    void registryDatabaseConstraintsRejectMissingCompaniesInvalidNamesAndStates() {
        rejected(db -> insertLocator(UUID.randomUUID(), "safecrop_op_a", "PENDING"));
        rejected(db -> insertLocator(null, "safecrop_op_a", "PENDING"));
        rejected(db -> insertLocator(insertCompany("registry-a"), "safecrop_op_a", "UNKNOWN"));
        rejected(db -> insertLocator(insertCompany("registry-a"), "safecrop_op_a", null));
        for (String invalid : new String[]{"safecrop_db", "postgres", "SAFECROP_OP_A", "safecrop_op_fárm", "safecrop_op_a?x=1", "safecrop_op_" + "a".repeat(52)}) {
            rejected(db -> insertLocator(insertCompany("registry-a"), invalid, "PENDING"));
        }
        rejected(db -> {
            UUID company = insertCompany("registry-a");
            insertLocator(company, "safecrop_op_a", "PENDING");
            db.update("delete from companies where id = ?", company);
        });
    }

    private void insertLocator(UUID company, String name, String state) {
        jdbc.update("insert into operational_databases (id, company_id, database_name, provisioning_state, created_at, updated_at) values (?, ?, ?, ?, now(), now())",
                UUID.randomUUID(), company, name, state);
    }

    @Test
    @Transactional
    void bootstrapRejectsSecondPlatformAdministrator() {
        String password = "test-only-integration-pass";
        accounts.bootstrapPlatformAdministrator("Platform", "platform-it@example.com", password);
        assertThatThrownBy(() -> accounts.bootstrapPlatformAdministrator("Other", "other-it@example.com", password))
                .isInstanceOf(org.safecrop.shared.exception.ApiFailure.class);
    }

    @Test
    @Transactional
    void persistedLoginAdministrationAndRevocationUseCentralPostgres() throws Exception {
        String password = "test-only-integration-pass";
        accounts.bootstrapPlatformAdministrator("Platform", "platform-it@example.com", password);
        String loginBody = json.writeValueAsString(java.util.Map.of("email", "platform-it@example.com", "password", password));
        String response = mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(loginBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String bearer = "Bearer " + json.readTree(response).get("accessToken").asText();
        String created = mvc.perform(post("/api/v1/admin/companies").header("Authorization", bearer).contentType("application/json")
                .content("{\"name\":\"Integration Farm\",\"slug\":\"integration-farm\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID companyId = UUID.fromString(json.readTree(created).get("id").asText());
        String adminBody = json.writeValueAsString(java.util.Map.of("name", "Manager", "email", "manager-it@example.com", "password", password));
        mvc.perform(post("/api/v1/admin/companies/" + companyId + "/administrators").header("Authorization", bearer).contentType("application/json").content(adminBody))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.passwordHash").doesNotExist());
        String companyLogin = json.writeValueAsString(java.util.Map.of("email", "manager-it@example.com", "password", password));
        response = mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(companyLogin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String companyBearer = "Bearer " + json.readTree(response).get("accessToken").asText();
        entityManager.clear();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", companyBearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.company.id").value(companyId.toString()));
        mvc.perform(get("/api/v1/admin/companies").header("Authorization", companyBearer)).andExpect(status().isForbidden());
        Company company = companies.findById(companyId).orElseThrow();
        company.deactivate();
        companies.flush();
        entityManager.clear();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", companyBearer)).andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void persistsCompaniesAndBothKindsOfUserAndUpdatesAuditTimestamp() {
        Company company = companies.saveAndFlush(new Company("Farm", "farm"));
        AppUser employee = users.saveAndFlush(new AppUser(company.getId(), "Employee", "EMPLOYEE@example.com", UserRole.EMPLOYEE));
        AppUser admin = users.saveAndFlush(new AppUser(null, "Platform", "platform@example.com", UserRole.PLATFORM_ADMIN));
        users.saveAndFlush(new AppUser(company.getId(), "Manager", "manager@example.com", UserRole.COMPANY_ADMIN));
        entityManager.clear();
        Company loaded = companies.findById(company.getId()).orElseThrow();
        var originalCreatedAt = loaded.getCreatedAt();
        assertThat(loaded.getSlug()).isEqualTo("farm");
        assertThat(users.findByEmail("employee@example.com").orElseThrow().getCompanyId()).isEqualTo(company.getId());
        assertThat(users.findById(admin.getId()).orElseThrow().getCompanyId()).isNull();
        assertThat(jdbc.queryForObject("select password_hash from app_users where id = ?", String.class, employee.getId())).isNull();
        loaded.rename("Renamed farm");
        companies.flush();
        entityManager.clear();
        loaded = companies.findById(company.getId()).orElseThrow();
        assertThat(loaded.getCreatedAt()).isEqualTo(originalCreatedAt);
        assertThat(loaded.getUpdatedAt()).isAfterOrEqualTo(loaded.getCreatedAt());
        assertThat(loaded.getName()).isEqualTo("Renamed farm");
    }

    @Test
    void databaseEnforcesSlugAndGlobalEmailUniquenessWithoutServiceChecks() {
        rejected(db -> {
            insertCompany("same");
            insertCompany("same");
        });
        rejected(db -> {
            UUID first = insertCompany("first");
            UUID second = insertCompany("second");
            insertUser(first, "same@example.com", "EMPLOYEE", "INACTIVE", null);
            insertUser(second, "same@example.com", "COMPANY_ADMIN", "INACTIVE", null);
        });
    }

    @Test
    void databaseRejectsInvalidRolesMembershipAndBrokenReferences() {
        rejected(db -> insertUser(null, "e@example.com", "EMPLOYEE", "INACTIVE", null));
        rejected(db -> insertUser(null, "e@example.com", "COMPANY_ADMIN", "INACTIVE", null));
        rejected(db -> insertUser(insertCompany("farm"), "e@example.com", "PLATFORM_ADMIN", "INACTIVE", null));
        rejected(db -> insertUser(null, "e@example.com", "UNKNOWN", "INACTIVE", null));
        rejected(db -> insertUser(UUID.randomUUID(), "e@example.com", "EMPLOYEE", "INACTIVE", null));
        rejected(db -> {
            UUID company = insertCompany("farm");
            insertUser(company, "e@example.com", "EMPLOYEE", "INACTIVE", null);
            db.update("delete from companies where id = ?", company);
        });
    }

    @Test
    void databaseRejectsInvalidStatusCredentialsEmailNamesAndSlugs() {
        rejected(db -> insertUser(null, "e@example.com", "PLATFORM_ADMIN", "ACTIVE", null));
        rejected(db -> insertUser(null, "e@example.com", "PLATFORM_ADMIN", "UNKNOWN", null));
        rejected(db -> insertUser(null, "E@EXAMPLE.COM", "PLATFORM_ADMIN", "INACTIVE", null));
        rejected(db -> insertUser(null, "invalid", "PLATFORM_ADMIN", "INACTIVE", null));
        rejected(db -> insertCompany("INVALID SLUG"));
        rejected(db -> {
            UUID id = insertCompany("farm");
            db.update("update companies set name = ' ' where id = ?", id);
        });
        rejected(db -> {
            UUID id = insertCompany("farm");
            db.update("update companies set status = 'UNKNOWN' where id = ?", id);
        });
        rejected(db -> insertUser(null, "e@example.com", "PLATFORM_ADMIN", "INACTIVE", " "));
    }

    private void rejected(Consumer<JdbcTemplate> operation) {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            operation.accept(jdbc);
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertCompany(String slug) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into companies (id, name, slug, status, created_at, updated_at) values (?, 'Farm', ?, 'ACTIVE', now(), now())", id, slug);
        return id;
    }

    private void insertUser(UUID company, String email, String role, String status, String hash) {
        jdbc.update("insert into app_users (id, company_id, name, email, role, status, password_hash, created_at, updated_at) values (?, ?, 'Name', ?, ?, ?, ?, now(), now())",
                UUID.randomUUID(), company, email, role, status, hash);
    }

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
    void flywayAppliesCentralIdentityMigrationAndIsRepeatable() {
        assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();
        assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
        assertThat(flyway.getConfiguration().getIgnoreMigrationPatterns()).isEmpty();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().applied()).hasSize(4);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' "
                        + "and table_type = 'BASE TABLE'", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select to_regclass('public.flyway_schema_history')::text",
                String.class)).isEqualTo("flyway_schema_history");
        // Uma segunda execução também deve ser segura e não aplicar scripts inexistentes.
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void httpAndDatabaseHealthAreAvailable() throws Exception {
        assertThat(healthContributors.getContributor("db")).isInstanceOf(HealthIndicator.class);
        assertThat(((HealthIndicator) healthContributors.getContributor("db")).health().getStatus().getCode()).isEqualTo("UP");
        mvc.perform(get("/api/v1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }
}
