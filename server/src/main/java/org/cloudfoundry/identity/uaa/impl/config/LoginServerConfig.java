package org.cloudfoundry.identity.uaa.impl.config;

import java.util.HashMap;
import java.util.Map;
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
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * All beans are marked lazy because they might never be used.
 * <p>
 * There two options for {@link MessageService}, either an {@link EmailService} which sends an e-mail or a
 * {@link NotificationsService} which makes an HTTP request. Notifications have precedence over e-mail,
 * which is the "fallback" option.
 */
@Lazy
@Configuration
@EnableConfigurationProperties(SmtpProperties.class)
public class LoginServerConfig {

    /**
     * Fallback bean for when there is no "notifications.url".
     * TODO: dgarnier annotate with @Fallback in Boot 3.4
     *
     * @return -
     */
    @Bean
    public MessageService emailMessageService(
            // dgarnier: use DEFAULT_UAA_URL
            @Value("${login.url:http://localhost:8080/uaa}") String loginUrl,
            JavaMailSender mailSender,
            SmtpProperties smtpProperties,
            IdentityZoneManager identityZoneManager) {
        return new EmailService(
                mailSender,
                loginUrl,
                smtpProperties.fromAddress(),
                identityZoneManager
        );
    }

    /**
     * Fallback for SMTP mail sender, when no real mail sender is used. This is mostly used in tests.
     *
     * @return -
     */
    @Bean
    JavaMailSender fakeJavaMailSender() {
        return new FakeJavaMailSender();
    }

    @Bean
    @Primary
    @ConditionalOnProperty(value = "smtp.host", matchIfMissing = false)
    JavaMailSender smtpMailSender(SmtpProperties smtpProperties) {
        var mailSender = new JavaMailSenderImpl();
        mailSender.setHost(smtpProperties.host());
        mailSender.setPort(smtpProperties.port());
        mailSender.setPassword(smtpProperties.password());
        mailSender.setUsername(smtpProperties.user());

        var javaMailProperties = new Properties();
        javaMailProperties.put("mail.smtp.auth", smtpProperties.auth());
        javaMailProperties.put("mail.smtp.starttls.enable", smtpProperties.starttls());
        javaMailProperties.put("mail.smtp.ssl.protocols", smtpProperties.sslprotocols());
        mailSender.setJavaMailProperties(javaMailProperties);
        return mailSender;
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
