package org.cloudfoundry.identity.uaa.extensions.database;

import org.apache.tomcat.jdbc.pool.DataSource;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.lang.Nullable;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Extension to reset database connections before and after a test.
 * It closes all existing connections. This is particularly useful when the
 * database connection sets session properties when they are opened.
 *
 * @see DatabaseTestUTC
 */
class ResetDatabaseConnectionsExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        var dataSource = getDataSourceOrNull(context);
        if (dataSource == null) {
            return;
        }
        dataSource.getPool().purge();
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        var dataSource = getDataSourceOrNull(context);
        if (dataSource == null) {
            return;
        }
        dataSource.getPool().purge();
    }


    @Nullable
    private DataSource getDataSourceOrNull(ExtensionContext context) {
        try {
            var applicationContext = SpringExtension.getApplicationContext(context);
            return applicationContext.getBean(DataSource.class);
        } catch (IllegalStateException ignore) {
            return null;
        }
    }
}
