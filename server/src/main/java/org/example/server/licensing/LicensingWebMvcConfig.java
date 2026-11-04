package org.example.server.licensing;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class LicensingWebMvcConfig implements WebMvcConfigurer {

    private final LicenseSecurityInterceptor licenseSecurityInterceptor;

    public LicensingWebMvcConfig(LicenseSecurityInterceptor licenseSecurityInterceptor) {
        this.licenseSecurityInterceptor = licenseSecurityInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(licenseSecurityInterceptor)
                .addPathPatterns("/api/**");
    }
}
