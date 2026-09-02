package com.razorrecall;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "razorrecall.webhook.secret=test_webhook_secret_key_12345")
class RazorrecallApplicationTests {

	@Test
	void contextLoads() {
	}

}
