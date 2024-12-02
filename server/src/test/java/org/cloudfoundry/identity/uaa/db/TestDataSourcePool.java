package org.cloudfoundry.identity.uaa.db;

import org.cloudfoundry.identity.uaa.annotations.WithDatabaseContext;
import org.cloudfoundry.identity.uaa.db.beans.DatabaseProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@WithDatabaseContext
class TestDataSourcePool {

    @Autowired
    private DatabaseProperties databaseProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testValidationQuery() {
        int i = jdbcTemplate.queryForObject(this.databaseProperties.getValidationQuery(), Integer.class);
        assertEquals(1, i);
    }

}
