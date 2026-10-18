package com.homelabhub.docs;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DocsProperties.class)
class DocsBeans {

    @Bean
    BlobStore blobStore(DocsProperties props) {
        return new BlobStore(Path.of(props.dataDir(), "blobs"));
    }

    @Bean
    PreviewTokens previewTokens(Clock clock) {
        return new PreviewTokens(clock);
    }
}
