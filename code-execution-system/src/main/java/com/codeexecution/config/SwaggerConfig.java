package com.codeexecution.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Code Execution System API")
                        .description("""
                                Secure backend system for executing user-submitted code.
                                
                                **Workflow:**
                                1. `POST /code-sessions` — Create a live coding session
                                2. `PATCH /code-sessions/{id}` — Autosave code as user types
                                3. `POST /code-sessions/{id}/run` — Submit code for async execution
                                4. `GET /executions/{id}` — Poll for result
                                
                                **Execution states:** `QUEUED → RUNNING → COMPLETED | FAILED | TIMEOUT`
                                """)
                        .version("1.0.0")
                        .contact(new Contact().name("Code Execution System")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local / Docker")
                ));
    }
}
