package ch.helpos.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${helpos.cors.allowed-origins:*}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var registration = registry.addMapping("/**")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);

        if (allowedOrigins.length == 1 && "*".equals(allowedOrigins[0])) {
            registration.allowedOriginPatterns("*").allowCredentials(false);
        } else {
            registration.allowedOrigins(allowedOrigins).allowCredentials(true);
        }
    }
}
