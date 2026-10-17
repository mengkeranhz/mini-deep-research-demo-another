package org.example.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** 扫描 skills/<name>/SKILL.md；系统提示只暴露摘要，load_skill 后才注入全文。 */
public final class SkillRegistry {

    private final List<Skill> skills;

    public SkillRegistry(Path projectRoot) {
        this.skills = scan(projectRoot.resolve("skills"));
    }

    public String promptSection() {
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("\n# 可用技能\n匹配时必须先调用 load_skill 获取完整流程：\n");
        skills.forEach(skill -> text.append("- ").append(skill.name())
                .append("：").append(skill.description()).append('\n'));
        return text.toString();
    }

    public Optional<Skill> find(String name) {
        String normalized = normalize(name);
        return skills.stream()
                .filter(skill -> skill.name().equals(name) || normalize(skill.name()).equals(normalized))
                .findFirst();
    }

    public List<String> names() {
        return skills.stream().map(Skill::name).toList();
    }

    public Path skillResource(String location) {
        if (location == null || !location.startsWith("skill://")) {
            throw new IllegalArgumentException("非法 skill 地址: " + location);
        }
        String rest = location.substring("skill://".length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw new IllegalArgumentException("skill 地址应为 skill://<name>/<relative-path>: " + location);
        }
        Skill skill = find(rest.substring(0, slash))
                .orElseThrow(() -> new IllegalArgumentException("技能不存在: " + location));
        return skill.files().stream()
                .filter(file -> file.equals(rest.substring(slash + 1)))
                .findFirst()
                .map(file -> Path.of(skill.directory()).resolve(file).toAbsolutePath().normalize())
                .orElseThrow(() -> new IllegalArgumentException("技能附件不存在: " + location));
    }

    private static List<Skill> scan(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> directories = Files.list(root)) {
            return directories
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(SkillRegistry::parse)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("扫描技能目录失败: " + root, e);
        }
    }

    private static Optional<Skill> parse(Path directory) {
        Path file = directory.resolve("SKILL.md");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (!content.startsWith("---\n")) {
                return Optional.empty();
            }
            int end = content.indexOf("\n---\n", 4);
            if (end < 0) {
                return Optional.empty();
            }
            String frontMatter = content.substring(4, end);
            String body = content.substring(end + 5).strip();
            String name = frontMatterValue(frontMatter, "name");
            String description = frontMatterValue(frontMatter, "description");
            if (name.isBlank()) {
                return Optional.empty();
            }
            List<String> files = new ArrayList<>();
            List<String> schemas = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(directory)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> !path.equals(file))
                        .forEach(path -> {
                            String relative = directory.relativize(path).toString().replace('\\', '/');
                            files.add(relative);
                            if (relative.startsWith("schemas/") && relative.endsWith(".json")) {
                                schemas.add(relative);
                            }
                        });
            }
            files.sort(Comparator.naturalOrder());
            schemas.sort(Comparator.naturalOrder());
            return Optional.of(new Skill(name, description, body, directory.toAbsolutePath().toString(), files, schemas));
        } catch (IOException e) {
            throw new IllegalStateException("读取技能失败: " + file, e);
        }
    }

    private static String frontMatterValue(String frontMatter, String key) {
        for (String line : frontMatter.split("\n")) {
            if (line.startsWith(key + ":")) {
                return line.substring(key.length() + 1).strip().replaceAll("^[\"']|[\"']$", "");
            }
        }
        return "";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase().replaceAll("[_\\s]+", "-");
    }
}
