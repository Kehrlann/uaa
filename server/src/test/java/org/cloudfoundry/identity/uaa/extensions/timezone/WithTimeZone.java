package org.cloudfoundry.identity.uaa.extensions.timezone;


import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Hardcode the timezone for a given test or class. After the test,
 * the default timezone is restored. Default use-case is to set the
 * timezone to UTC.
 * <p>
 * This is important for MySQL-based tests, as MySQL and the JDBC driver
 * do not handle timezones consistently depending on the datatypes used.
 * If the machine running the tests is on a timezone ahead of UTC, it might
 * have trouble fetching events "in the past".
 * <p>
 * Usage:
 *
 * <pre>
 * class MyTest {
 *      &#64;Test
 *      &#64;WithTimeZone("UTC")
 *      void useUtc() {
 *          // ...
 *      }
 *
 *      &#64;Test
 *      &#64;WithTimeZone("Europe/Paris")
 *      void useParisTz() {
 *          // ...
 *      }
 * }
 * </pre>
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(TimeZoneExtension.class)
public @interface WithTimeZone {

    String UTC = "UTC";

    String value();
}
