package com.assessment.fundtransfer.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI fundTransferOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Fund Transfer Assessment API")
                        .description("Version 1 of the REST API for creating funded customer accounts, retrieving current balances, and transferring funds between persisted customer accounts. Both POST endpoints require an Idempotency-Key header. Exact retries replay the original response, including concurrent identical retries. Monetary amounts are accepted and stored at NUMERIC(19,2) precision. Error responses use a consistent envelope that includes timestamp, path, errorCode, correlationId, and structured validation details when applicable.")
                        .version("v1")
                        .contact(new Contact()
                                .name("Assessment API Support")
                                .email("api-support@example.com"))
                        .license(new License()
                                .name("Assessment Use Only")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("Local development server")
                ));
    }
}
