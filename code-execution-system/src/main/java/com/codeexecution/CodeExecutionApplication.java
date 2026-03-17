package com.codeexecution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CodeExecutionApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodeExecutionApplication.class, args);
    }
}
