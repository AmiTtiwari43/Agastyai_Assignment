package com.company.webhooks.endpoint;

import com.company.webhooks.common.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {

    @Test
    @DisplayName("Valid public HTTPS and HTTP URLs are accepted")
    void testValidUrls() {
        UrlValidator validator = new UrlValidator(false);

        assertThatCode(() -> validator.validateUrl("https://api.example.com/webhooks"))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateUrl("http://hooks.slack.com/services/123"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Invalid schemes (ftp, ws, mailto) are rejected")
    void testInvalidSchemes() {
        UrlValidator validator = new UrlValidator(false);

        assertThatThrownBy(() -> validator.validateUrl("ftp://example.com/hook"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("scheme must be http or https");

        assertThatThrownBy(() -> validator.validateUrl("javascript:alert(1)"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("SSRF Protection: Localhost and loopback IPs are rejected when allowInternalUrls=false")
    void testLocalhostAndLoopbackRejection() {
        UrlValidator validator = new UrlValidator(false);

        assertThatThrownBy(() -> validator.validateUrl("http://localhost:8080/hook"))
                .isInstanceOf(BadRequestException.class);

        assertThatThrownBy(() -> validator.validateUrl("http://127.0.0.1:9000/hook"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("Internal URLs are accepted when allowInternalUrls=true")
    void testInternalUrlsAllowedInDevMode() {
        UrlValidator validator = new UrlValidator(true);

        assertThatCode(() -> validator.validateUrl("http://localhost:8080/hook"))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateUrl("http://127.0.0.1:9000/hook"))
                .doesNotThrowAnyException();
    }
}
