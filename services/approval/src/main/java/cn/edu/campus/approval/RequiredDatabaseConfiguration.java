package cn.edu.campus.approval;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Rejects missing credentials before an idle connection pool can hide the configuration error. */
final class RequiredDatabaseConfiguration
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (context.getEnvironment().acceptsProfiles(org.springframework.core.env.Profiles.of("shell"))) return;
        for (String property : new String[]{"spring.datasource.username", "spring.datasource.password",
                "spring.flyway.user", "spring.flyway.password"}) {
            String value;
            try {
                value = context.getEnvironment().getRequiredProperty(property);
            } catch (IllegalArgumentException missing) {
                throw new IllegalStateException("Missing required database configuration: " + property);
            }
            if (value.isBlank()) {
                throw new IllegalStateException("Empty required database configuration: " + property);
            }
        }
    }
}
