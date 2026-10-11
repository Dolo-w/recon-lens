package io.github.dolow.reconlens;

import io.github.dolow.reconlens.config.ReconProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ReconProperties.class)
public class ReconLensApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReconLensApplication.class, args);
    }
}
