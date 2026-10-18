package com.homelabhub.internal;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serves the user's own icons from {data}/images at /images/... (read-only; Spring rejects ".." in paths). */
@Configuration
class WebConfig implements WebMvcConfigurer {

    private final String imagesDir;

    WebConfig(@Value("${HUB_DATA_DIR:./data}") String dataDir, @Value("${hub.images.dir:}") String configured) {
        this.imagesDir = (configured == null || configured.isBlank() ? Path.of(dataDir, "images") : Path.of(configured)).toAbsolutePath().toUri().toString();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/images/**").addResourceLocations(imagesDir);
    }
}
