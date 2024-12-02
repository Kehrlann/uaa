package org.cloudfoundry.identity.uaa.db.beans;

import javax.sql.DataSource;
import org.cloudfoundry.identity.uaa.db.FixFailedBackportMigrations_4_0_4;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

@Configuration
public class FlywayConfiguration {

  /**
   * In Flyway 5, the default version table name changed to flyway_schema_history
   * https://flywaydb.org/documentation/releaseNotes#5.0.0
   * https://github.com/flyway/flyway/issues/1848
   * <p>
   * We need to maintain backwards compatibility due to {@link FixFailedBackportMigrations_4_0_4}
   */
  static final String VERSION_TABLE = "schema_version";

  @Bean
  public Flyway baseFlyway(
          DataSource dataSource,
          DatabaseProperties databaseProperties) { // TODO dgarnier, infer platform from env
    Flyway flyway = Flyway.configure()
        .baselineOnMigrate(true)
        .dataSource(dataSource)
        .locations("classpath:org/cloudfoundry/identity/uaa/db/" + databaseProperties.getType() + "/")
        .baselineVersion("1.5.2")
        .validateOnMigrate(false)
        .table(VERSION_TABLE)
        .load();
    return flyway;
  }

  private static final String MIGRATIONS_ENABLED = "uaa.migrationsEnabled";

  @Configuration
  @Conditional(FlywayConfigurationWithMigration.ConfiguredWithMigrations.class)
  public static class FlywayConfigurationWithMigration {
    static class ConfiguredWithMigrations implements Condition {

      @Override
      public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var migrationsEnabled = context.getEnvironment().getProperty(MIGRATIONS_ENABLED, "true");
        return !migrationsEnabled.equals("false");
      }
    }

    @Bean
    public Flyway flyway(Flyway baseFlyway) {
      baseFlyway.repair();
      baseFlyway.migrate();
      return baseFlyway;
    }
  }

  @Configuration
  @Conditional(FlywayConfigurationWithoutMigrations.ConfiguredWithoutMigrations.class)
  static class FlywayConfigurationWithoutMigrations {

    static class ConfiguredWithoutMigrations implements Condition {

      @Override
      public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var migrationsEnabled = context.getEnvironment().getProperty(MIGRATIONS_ENABLED, "true");
        return migrationsEnabled.equals("false");
      }
    }

    @Bean
    public Flyway flyway(Flyway baseFlyway) {
      return baseFlyway;
    }
  }
}

