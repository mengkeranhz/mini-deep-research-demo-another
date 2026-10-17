package org.example.skill;

import java.util.List;

public record Skill(
        String name,
        String description,
        String instructions,
        String directory,
        List<String> files,
        List<String> schemas
) {
}
