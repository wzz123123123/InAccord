package com.inforvans.accord.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

class AccordHttpTelemetryAutoConfigurationTest {
    @Test
    void registersForControlApi() {
        assertRegistration("accord-control-api");
    }

    @Test
    void registersForWebhookEdge() {
        assertRegistration("accord-webhook-edge");
    }

    @Test
    void exposesWorkflowProducerWithoutServletFilterInWorker() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AccordOpenTelemetryConfiguration.SpringAutoConfiguration.class))
                .withPropertyValues("spring.application.name=accord-control-worker")
                .run(context -> {
                    assertThat(context).hasSingleBean(AccordWorkflowTelemetry.class);
                    assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
                });
    }

    private static void assertRegistration(String applicationName) {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AccordOpenTelemetryConfiguration.SpringAutoConfiguration.class))
                .withPropertyValues("spring.application.name=" + applicationName)
                .run(context -> {
                    assertThat(context).hasSingleBean(AccordWorkflowTelemetry.class);
                    assertThat(context).hasSingleBean(FilterRegistrationBean.class);
                    FilterRegistrationBean<?> registration =
                            context.getBean(FilterRegistrationBean.class);
                    assertThat(registration.getFilter())
                            .isExactlyInstanceOf(AccordHttpTelemetryFilter.class);
                    assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
                    assertThat(registration.getUrlPatterns()).containsExactly("/*");
                });
    }
}
