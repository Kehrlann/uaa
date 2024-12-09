package org.cloudfoundry.identity.uaa.db.beans;


import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;

class DatabasePropertiesTest {

    @Nested
    @ActiveProfiles("default")
    @ExtendWith(SpringExtension.class)
    @Import(DatabaseConfiguration.class)
    class Defaults {

        @Autowired
        DatabaseProperties properties;

        @Test
        void configuration() {
            assertThat(properties.getDriverClassName()).isEqualTo("org.hsqldb.jdbcDriver");
            assertThat(properties.getUsername()).isEqualTo("sa");
            assertThat(properties.getPassword()).isNullOrEmpty();
            assertThat(properties.getMaxParameters()).isEqualTo(-1);
            assertThat(properties.isUseSkipLocked()).isFalse();
            assertThat(properties.isCaseinsensitive()).isFalse();

            assertThat(properties.getType()).isEqualTo("hsqldb");
            assertThat(properties.getValidationQuery()).isEqualTo("select 1 from information_schema.system_users");
            // DB name may be uaa_* when running from Gradle
            assertThat(properties.getUrl()).matches("jdbc:hsqldb:mem:uaa(_\\d+)?");
        }
    }

    @Nested
    @ActiveProfiles("postgresql")
    @ExtendWith(SpringExtension.class)
    @Import(DatabaseConfiguration.class)
    class PostgreSQL {

        @Autowired
        DatabaseProperties properties;

        @Test
        void configuration() {
            assertThat(properties.getDriverClassName()).isEqualTo("org.postgresql.Driver");
            assertThat(properties.getUsername()).isEqualTo("root");
            assertThat(properties.getPassword()).isEqualTo("changeme");
            assertThat(properties.getMaxParameters()).isEqualTo(32767);
            assertThat(properties.isUseSkipLocked()).isTrue();
            assertThat(properties.isCaseinsensitive()).isFalse();

            assertThat(properties.getType()).isEqualTo("postgresql");
            assertThat(properties.getValidationQuery()).isEqualTo("select 1");
            // DB name may be uaa_* when running from Gradle
            assertThat(properties.getUrl()).matches("jdbc:postgresql:uaa(_\\d+)?");
        }
    }

    @Nested
    @ActiveProfiles("mysql")
    @ExtendWith(SpringExtension.class)
    @Import(DatabaseConfiguration.class)
    class MySQL {

        @Autowired
        DatabaseProperties properties;

        @Test
        void configuration() {
            assertThat(properties.getDriverClassName()).isEqualTo("org.mariadb.jdbc.Driver");
            assertThat(properties.getUsername()).isEqualTo("root");
            assertThat(properties.getPassword()).isEqualTo("changeme");
            assertThat(properties.getMaxParameters()).isEqualTo(-1);
            assertThat(properties.isUseSkipLocked()).isFalse();
            assertThat(properties.isCaseinsensitive()).isTrue();

            assertThat(properties.getType()).isEqualTo("mysql");
            assertThat(properties.getValidationQuery()).isEqualTo("select 1");
            // DB name may be uaa_* when running from Gradle
            assertThat(properties.getUrl()).matches("jdbc:mysql://127\\.0\\.0\\.1:3306/uaa(_\\d+)?\\?useSSL=true&trustServerCertificate=true");
        }
    }

}
