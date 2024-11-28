package org.cloudfoundry.identity.uaa.impl.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.cloudfoundry.identity.uaa.message.EmailService;
import org.cloudfoundry.identity.uaa.message.LocalUaaRestTemplate;
import org.cloudfoundry.identity.uaa.message.MessageService;
import org.cloudfoundry.identity.uaa.message.MessageType;
import org.cloudfoundry.identity.uaa.message.NotificationsService;
import org.cloudfoundry.identity.uaa.message.util.FakeJavaMailSender;
import org.cloudfoundry.identity.uaa.zone.beans.IdentityZoneManager;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(SmtpProperties.class)
public class LoginServerConfig {

    /**
     * Fallback bean for when there is no "notifications.url". Lazy because we may not need to wire this bean
     * if the {@link NotificationsService} is present.
     *
     * @return -
     */
    @Lazy
    @Bean
    public MessageService emailMessageService(
            Environment environment,
            // dgarnier: use UAA_DEFAULT_UR
            @Value("${login.url:http://localhost:8080/uaa}") String loginUrl,
            SmtpProperties smtpProperties,
            IdentityZoneManager identityZoneManager) {

        var mailSender = Optional.ofNullable(environment.getProperty("smtp.host"))
                .filter(StringUtils::hasText)
                .<JavaMailSender>map(host -> smtpMailSender(smtpProperties))
                .orElseGet(FakeJavaMailSender::new);

        return new EmailService(
                mailSender,
                loginUrl,
                smtpProperties.fromAddress(),
                identityZoneManager
        );
    }

    private static JavaMailSenderImpl smtpMailSender(SmtpProperties smtpProperties) {
        var realMailSender = new JavaMailSenderImpl();
        realMailSender.setHost(smtpProperties.host());
        realMailSender.setPort(smtpProperties.port());
        realMailSender.setPassword(smtpProperties.password());
        realMailSender.setUsername(smtpProperties.user());

        var javaMailProperties = new Properties();
        javaMailProperties.put("mail.smtp.auth", smtpProperties.auth());
        javaMailProperties.put("mail.smtp.starttls.enable", smtpProperties.starttls());
        javaMailProperties.put("mail.smtp.ssl.protocols", smtpProperties.sslprotocols());
        realMailSender.setJavaMailProperties(javaMailProperties);
        return realMailSender;
    }

    @Configuration
    @ConditionalOnProperty(value = "notifications.url", matchIfMissing = false)
    static class NotificationConfiguration {

        @Bean
        @Primary
        public MessageService notificationMessageService(
                @Value("${notifications.url}") String notificationsUrl,
                LocalUaaRestTemplate notificationsTemplate,
                @Value("${notifications.send_in_default_zone:true}") boolean sendInDefaultZone
        ) {
            return new NotificationsService(
                    notificationsTemplate,
                    notificationsUrl,
                    notifications(),
                    sendInDefaultZone
            );
        }

        private static Map<MessageType, HashMap<String, Object>> notifications() {
            return Map.of(
                    MessageType.CREATE_ACCOUNT_CONFIRMATION, notification("Send activation code", "f7a85fdc-d920-41f0-b3a4-55db08e408ce"),
                    MessageType.PASSWORD_RESET, notification("Reset Password", "141200f6-93bd-4761-a721-941ab511ba2c"),
                    MessageType.CHANGE_EMAIL, notification("Change Email", "712de257-a7fa-44cb-b1ac-8a6588d1be23"),
                    MessageType.INVITATION, notification("Invitation", "e6722687-3f0f-4e7a-9925-839a04712cea")
            );
        }

        private static HashMap<String, Object> notification(String description, String id) {
            return new HashMap<>(
                    Map.of(
                            "description", description,
                            "id", id,
                            "critical", true
                    )
            );
        }

    }

}
