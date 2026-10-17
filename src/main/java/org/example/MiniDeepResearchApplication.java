package org.example;

import org.example.config.ResearchProperties;
import org.example.skill.SkillRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

@SpringBootApplication
@EnableConfigurationProperties(ResearchProperties.class)
public class MiniDeepResearchApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(MiniDeepResearchApplication.class, args)));
    }

    @Bean
    SkillRegistry skillRegistry(ResearchProperties properties) {
        return new SkillRegistry(Path.of(properties.projectRoot()));
    }
}
