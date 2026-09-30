package io.duo.sim.control.library;

import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.scenario.ScenarioLoader;
import io.duo.sim.scenario.ScenarioValidator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 场景库（M10 计划一 Task 3）：内置模板（classpath {@code /console-templates/}，只读）
 * + 用户库（{@code --library-dir} 目录，可写）。
 *
 * <p>安全口径（设计 §7）：id 白名单 {@code [A-Za-z0-9._-]+}——路径分隔符、{@code ..}、
 * 空串一律 {@link IllegalArgumentException}，写入严格限定在 userDir 内；模板 id 对
 * 写/删一律 {@link IllegalStateException}（REST 层映射 405）。
 *
 * <p>{@link #validateYaml} 是**外部输入档**全量校验（规则 1–8 + 9–11）：
 * {@link ScenarioValidator} 以 {@code InputTrust.EXTERNAL} 构造，与 {@code POST /scenario}
 * 同档——经 Web 上传的 YAML 不因「存成了文件」而升档（设计 §7.4）。
 */
public final class ScenarioLibrary {

    private static final String TEMPLATES_ROOT = "/console-templates";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]+");

    private final Path userDir;

    public ScenarioLibrary(Path userDir) throws IOException {
        this.userDir = userDir.toAbsolutePath().normalize();
        Files.createDirectories(this.userDir);
    }

    public Path userDir() {
        return userDir;
    }

    public static boolean validId(String id) {
        return id != null && !id.isBlank() && ID.matcher(id).matches();
    }

    /** 场景条目。{@code valid=false} 表示文件存在但解析/校验不过（列表不得因此 500）。 */
    public record Entry(String id, String name, boolean template, boolean valid,
                        int nodeCount, long lastModifiedMillis) {
    }

    public record Content(String id, boolean template, String yaml) {
    }

    private static void requireValidId(String id) {
        if (!validId(id)) {
            throw new IllegalArgumentException(
                    "invalid scenario id: only [A-Za-z0-9._-] are allowed");
        }
    }

    private boolean isTemplate(String id) {
        try (var in = ScenarioLibrary.class.getResourceAsStream(TEMPLATES_ROOT + "/index.txt")) {
            if (in == null) {
                return false;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim).anyMatch(id::equals);
        } catch (IOException e) {
            return false;
        }
    }

    private String readTemplate(String id) throws IOException {
        try (var in = ScenarioLibrary.class.getResourceAsStream(TEMPLATES_ROOT + "/" + id + ".yaml")) {
            if (in == null) {
                throw new NoSuchFileException(id);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public List<Entry> list() throws IOException {
        List<Entry> out = new ArrayList<>();
        try (var in = ScenarioLibrary.class.getResourceAsStream(TEMPLATES_ROOT + "/index.txt")) {
            if (in != null) {
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                        .map(String::trim).filter(s -> !s.isEmpty()).toList()) {
                    out.add(describe(line, true, readTemplate(line), 0L));
                }
            }
        }
        if (Files.isDirectory(userDir)) {
            try (var files = Files.list(userDir)) {
                for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".yaml"))
                        .sorted().toList()) {
                    String id = p.getFileName().toString().replaceFirst("\\.yaml$", "");
                    out.add(describe(id, false, Files.readString(p, StandardCharsets.UTF_8),
                            Files.getLastModifiedTime(p).toMillis()));
                }
            }
        }
        return out;
    }

    private Entry describe(String id, boolean template, String yaml, long lastModified) {
        try {
            var scenario = ScenarioLoader.load(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
            return new Entry(id, scenario.name(), template, true,
                    scenario.nodes().size(), lastModified);
        } catch (RuntimeException e) {
            return new Entry(id, id, template, false, 0, lastModified);
        }
    }

    public Content get(String id) throws IOException {
        requireValidId(id);
        if (isTemplate(id)) {
            return new Content(id, true, readTemplate(id));
        }
        Path p = userDir.resolve(id + ".yaml");
        if (!Files.exists(p)) {
            throw new NoSuchFileException(id);
        }
        return new Content(id, false, Files.readString(p, StandardCharsets.UTF_8));
    }

    public void put(String id, String yaml) throws IOException {
        requireValidId(id);
        if (isTemplate(id)) {
            throw new IllegalStateException("template scenarios are read-only: " + id);
        }
        Files.writeString(userDir.resolve(id + ".yaml"), yaml, StandardCharsets.UTF_8);
    }

    public void delete(String id) throws IOException {
        requireValidId(id);
        if (isTemplate(id)) {
            throw new IllegalStateException("template scenarios are read-only: " + id);
        }
        Path p = userDir.resolve(id + ".yaml");
        if (!Files.exists(p)) {
            throw new NoSuchFileException(id);
        }
        Files.delete(p);
    }

    public String fork(String sourceId, String newId) throws IOException {
        requireValidId(newId);
        if (isTemplate(newId) || Files.exists(userDir.resolve(newId + ".yaml"))) {
            throw new IllegalStateException("scenario id already exists: " + newId);
        }
        String yaml = get(sourceId).yaml(); // 源可以是模板或用户场景；get 自带 id 校验
        put(newId, yaml);                   // put 自带 newId 校验与模板冲突拒绝
        return newId;
    }

    /**
     * 外部输入档全量校验（规则 1–8 + 9–11）。返回**逐条**错误清单；合法 = 空表。
     * 解析失败（YAML 语法/结构）作为单条错误返回。
     */
    public List<String> validateYaml(String yaml) {
        try {
            var scenario = ScenarioLoader.load(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
            var report = new ScenarioValidator(ContractRegistry.loadFromServiceLoader(),
                    ScenarioValidator.InputTrust.EXTERNAL).validate(scenario);
            return report.ok() ? List.of() : List.copyOf(report.errors());
        } catch (RuntimeException e) {
            return List.of(String.valueOf(e.getMessage()));
        }
    }
}
