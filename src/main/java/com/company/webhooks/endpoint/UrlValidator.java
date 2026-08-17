package com.company.webhooks.endpoint;

import com.company.webhooks.common.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

@Component
public class UrlValidator {

    private final boolean allowInternalUrls;

    public UrlValidator(@Value("${webhooks.allow-internal-urls:false}") boolean allowInternalUrls) {
        this.allowInternalUrls = allowInternalUrls;
    }

    public void validateUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BadRequestException("Endpoint URL cannot be null or empty");
        }

        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid endpoint URL format: " + ex.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new BadRequestException("Endpoint URL scheme must be http or https");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BadRequestException("Endpoint URL must have a valid host");
        }

        if (!allowInternalUrls) {
            validateNotInternalHost(host);
        }
    }

    private void validateNotInternalHost(String host) {
        if (host.equalsIgnoreCase("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) {
            throw new BadRequestException("Localhost and internal domains are not permitted for webhook endpoints");
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (address.isLoopbackAddress()) {
                    throw new BadRequestException("Loopback addresses are not permitted for webhook endpoints");
                }
                if (address.isSiteLocalAddress()) {
                    throw new BadRequestException("Private network IP addresses (RFC 1918) are not permitted");
                }
                if (address.isLinkLocalAddress()) {
                    throw new BadRequestException("Link-local addresses are not permitted");
                }
                if (address.isMulticastAddress() || address.isAnyLocalAddress()) {
                    throw new BadRequestException("Special IP addresses are not permitted");
                }
            }
        } catch (UnknownHostException ex) {
            // DNS resolution failure will fail at delivery time, but we don't reject registration solely due to transient DNS
        }
    }
}
