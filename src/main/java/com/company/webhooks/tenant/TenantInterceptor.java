package com.company.webhooks.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class TenantInterceptor implements HandlerInterceptor {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    private final TenantService tenantService;

    public TenantInterceptor(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();

        // Skip non-API paths (actuator, swagger, error)
        if (!path.startsWith("/api/")) {
            return true;
        }

        String tenantId = request.getHeader(TENANT_HEADER);
        if (tenantId == null || tenantId.trim().isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            try {
                response.getWriter().write("{\"status\":400,\"error\":\"Bad Request\",\"message\":\"Missing required header: X-Tenant-Id\"}");
            } catch (Exception ignored) {}
            return false;
        }

        String sanitizedTenantId = tenantId.trim();
        tenantService.ensureTenantExists(sanitizedTenantId);
        TenantContext.setTenantId(sanitizedTenantId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear();
    }
}
