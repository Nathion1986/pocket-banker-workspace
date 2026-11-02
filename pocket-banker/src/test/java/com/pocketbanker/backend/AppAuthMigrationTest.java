package com.pocketbanker.backend;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AppAuthMigrationTest {
  @Test
  void v15DataSurvivesAppendOnlyUpgradeAndRepeatedMigrationIsEmpty() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:auth_upgrade;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
    Flyway.configure().dataSource(source).target("15").load().migrate();
    var jdbc = new JdbcTemplate(source);
    jdbc.update(
        "INSERT INTO backend_users(user_id,updated_at) VALUES('sentinel-existing-user',CURRENT_TIMESTAMP)");
    var before = jdbc.queryForList("SELECT * FROM backend_users");
    var flyway = Flyway.configure().dataSource(source).load();
    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
    assertThat(jdbc.queryForList("SELECT * FROM backend_users")).isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_accounts", Integer.class)).isZero();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
  }
}
