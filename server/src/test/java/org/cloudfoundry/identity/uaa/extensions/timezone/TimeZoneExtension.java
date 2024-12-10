package org.cloudfoundry.identity.uaa.extensions.timezone;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.platform.commons.util.AnnotationUtils;

import java.util.Optional;
import java.util.TimeZone;

/**
 * Extension to hardcode the timezone in a test.
 *
 * @see WithTimeZone
 * @author Daniel Garnier-Moiroux
 */
class TimeZoneExtension implements BeforeEachCallback, AfterEachCallback {
    private static final Namespace NAMESPACE = Namespace.create(TimeZoneExtension.class);
    private static final String TIMEZONE_KEY = "default_timezone";

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        getAnnotationForMethod(context)
                .or(() -> getAnnotationForClass(context))
                .map(WithTimeZone::value)
                .map(TimeZone::getTimeZone)
                .ifPresent(desiredTimeZone -> {
                    var currentTimeZone = TimeZone.getDefault();
                    context.getStore(NAMESPACE).put(TIMEZONE_KEY, currentTimeZone);

                    TimeZone.setDefault(desiredTimeZone);
                });
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        var defaultTimeZone = context.getStore(NAMESPACE).remove(TIMEZONE_KEY, TimeZone.class);
        TimeZone.setDefault(defaultTimeZone);
    }

    private static Optional<WithTimeZone> getAnnotationForMethod(ExtensionContext context) {
        return context.getTestMethod()
                .flatMap(method -> AnnotationUtils.findAnnotation(method, WithTimeZone.class));
    }

    private static Optional<WithTimeZone> getAnnotationForClass(ExtensionContext context) {
        return context.getTestClass()
                .flatMap(clazz -> AnnotationUtils.findAnnotation(clazz, WithTimeZone.class, true));
    }
}
