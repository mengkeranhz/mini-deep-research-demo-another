package org.example.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SkillRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesFrontMatterAndListsAttachments() throws IOException {
        Path directory = Files.createDirectories(tempDir.resolve("skills").resolve("demo"));
        Files.writeString(directory.resolve("SKILL.md"), """
                ---
                name: demo
                description: demo skill
                ---

                # Demo Workflow

                Do research.
                """);
        Files.createDirectories(directory.resolve("reference"));
        Files.writeString(directory.resolve("reference/data.json"), "{}");

        SkillRegistry registry = new SkillRegistry(tempDir);

        assertThat(registry.names()).containsExactly("demo");
        assertThat(registry.promptSection()).contains("demo：demo skill");
        assertThat(registry.find("demo")).isPresent();
        assertThat(registry.skillResource("skill://demo/reference/data.json"))
                .isEqualTo(directory.resolve("reference/data.json").normalize());
    }
}
