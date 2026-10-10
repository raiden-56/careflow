package com.careflow.hms.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI careflowOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CareFlow HMS API")
                        .version("1.0.0")
                        .description("""
                                REST API for CareFlow HMS. Authenticate with POST /api/auth/login.
                                Browser clients use httpOnly cookies; API tools may send the access token as a Bearer token.
                                Every sensitive endpoint enforces role permissions AND object-level access checks server-side.
                                Monetary values are decimal strings in INR. Timestamps are ISO-8601 UTC."""))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
