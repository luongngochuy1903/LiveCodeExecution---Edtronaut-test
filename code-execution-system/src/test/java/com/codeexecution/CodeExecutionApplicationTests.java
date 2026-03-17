package com.codeexecution;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class CodeExecutionApplicationTests {

    @Test
    void contextLoads() {
        // Basic smoke test
    }
}
