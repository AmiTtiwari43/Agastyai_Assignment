package com.company.webhooks.tenant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService {

    private final TenantRepository tenantRepository;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public Tenant ensureTenantExists(String tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseGet(() -> tenantRepository.save(new Tenant(tenantId, "Tenant " + tenantId)));
    }
}
