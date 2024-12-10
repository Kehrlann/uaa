package org.cloudfoundry.identity.uaa.extensions.database;


import org.cloudfoundry.identity.uaa.extensions.timezone.WithTimeZone;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Force the JVM timezone to be UTC for the current test, and reset all
 * database connections. This is required because MySQL and Possgres have
 * different behavior when it comes to timezone. When passing a time parameter
 * value to a JDBC template behaves in the same way, it is converted to the
 * JVM timezone. But calling {@code current_timestamp} has different behavior:
 *
 * <ul>
 * <li>In MySQL, it returns UTC-based time.</li>
 * <li>In Possgres, it returns timezone-sensitive timestamp. The selected timezone is the JVM timezone <b>whenever the connection was first opened</b>.</li>
 * </ul>
 * Timezone-sensitive tests are broken in MySQL when ran in a timezone ahead of UTC,
 * unless the JVM timezone is forced to be UTC.
 * <p>
 * Timezone-sensitive tests are broken in Postgres when the timezone is forced to UTC
 * right before a test and you are in a timezone behind UTC.
 * <p>
 * This annotation forces the timezone to be UTC, and then resets all connections, so that
 * Possgres connections match the JVM timezone. After a test, timezone is restored and
 * connections are reset once more.
 * <p>
 * It sets the correct order for {@link WithTimeZone} and {@link ResetDatabaseConnectionsExtension},
 * so that the TimeZone is always correct before connections are reset.
 *
 * @see WithTimeZone
 * @see ResetDatabaseConnectionsExtension
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@WithTimeZone(WithTimeZone.UTC)
@ExtendWith(ResetDatabaseConnectionsExtension.class)
public @interface DatabaseTestUTC {
}
