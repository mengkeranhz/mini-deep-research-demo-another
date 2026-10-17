package org.example.state;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;

/** 每次运行一个 Markdown 归档目录；只记录过程状态，不记录 API key。 */
public final class RunArchive {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path directory;

    public RunArchive(Path root) {
        try {
            directory = Files.createDirectories(root)
                    .resolve("run-" + LocalDateTime.now().format(FORMAT));
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("创建运行归档失败: " + root, e);
        }
    }

    public Path directory() {
        return directory;
    }

    public void write(String name, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        try {
            Path file = directory.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入归档失败: " + name, e);
        }
    }

    public String markdownIndex() {
        try (Stream<Path> files = Files.walk(directory, 2)) {
            StringBuilder text = new StringBuilder("# Archive Index\n");
            files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> directory.relativize(path).toString()))
                    .forEach(file -> text.append("- ").append(file.getFileName()).append('\n'));
            return text.toString();
        } catch (IOException e) {
            return "# Archive Index\n\n索引生成失败: " + e.getMessage();
        }
    }
}
