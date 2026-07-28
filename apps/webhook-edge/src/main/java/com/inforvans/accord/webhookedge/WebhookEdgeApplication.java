package com.inforvans.accord.webhookedge;

import com.inforvans.accord.webhookedge.binding.BindingResolver;
import com.inforvans.accord.webhookedge.binding.FileBindingResolver;
import com.inforvans.accord.webhookedge.security.GitLabWebhookVerifier;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(proxyBeanMethods = false)
public class WebhookEdgeApplication {
    public static void main(String[] arguments) {
        SpringApplication.run(WebhookEdgeApplication.class, arguments);
    }

    @Bean
    Clock webhookClock() {
        return Clock.systemUTC();
    }

    @Bean
    FileBindingResolver webhookBindingResolver(
            @Value("${accord.webhook-edge.bindings-file}") String bindingsFile) {
        return new FileBindingResolver(Path.of(bindingsFile));
    }

    @Bean("bindingProjection")
    HealthIndicator bindingProjectionHealthIndicator(FileBindingResolver bindingResolver) {
        return () -> {
            try {
                bindingResolver.validateProjection();
                return Health.up().build();
            } catch (BindingResolver.UnavailableException exception) {
                return Health.down().build();
            }
        };
    }

    @Bean
    GitLabWebhookVerifier gitLabWebhookVerifier(Clock webhookClock) {
        return new GitLabWebhookVerifier(webhookClock);
    }
}
