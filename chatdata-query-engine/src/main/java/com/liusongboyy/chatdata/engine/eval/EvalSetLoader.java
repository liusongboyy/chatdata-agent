package com.liusongboyy.chatdata.engine.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 从 JSON 文件读评测集。
 *
 * <h2>为什么是文件而不是库表</h2>
 *
 * <p>评测集是**验收基线**，要跟着代码一起评审、一起回滚。放库里会失去版本关联：
 * 改了一条 gold SQL，代码 diff 里看不到；回滚代码时基线也不会跟着回去，
 * 于是"同一份代码两次跑出不同分数"这件事将无法解释。
 *
 * <p>未知字段直接报错（{@code FAIL_ON_UNKNOWN_PROPERTIES=true}）：写错一个
 * {@code columOrder} 不该被静默忽略，然后按默认值跑出一份看着正常的报告。
 */
public final class EvalSetLoader {

    private static final ObjectMapper M = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private EvalSetLoader() {
    }

    public static EvalSet load(Path path) {
        try {
            EvalSet set = M.readValue(path.toFile(), EvalSet.class);
            // 记录组件已经校验过内容，这里补一层路径上下文——
            // 否则"用例缺少 goldSql"看不出是哪个文件
            return set;
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "读取评测集失败：" + path.toAbsolutePath() + " —— " + e.getMessage(), e);
        }
    }

    /**
     * 加载一个评测集文件，或一个目录下的全部 {@code *.json}。
     *
     * <p>目录模式是给"多数据源"准备的：每接一个新数据集，就多一个文件，
     * 不需要改配置、不需要改代码。文件按名称排序，保证同样的输入永远得到同样的运行顺序
     * （报告可对照）。
     *
     * @throws IllegalArgumentException 路径不存在、目录为空、或任一文件格式错误
     */
    public static List<EvalSet> loadAll(Path path) {
        if (Files.isDirectory(path)) {
            List<Path> files;
            try (var stream = Files.list(path)) {
                files = stream
                        .filter(p -> Files.isRegularFile(p)
                                && p.getFileName().toString().endsWith(".json"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
            } catch (IOException e) {
                throw new IllegalArgumentException(
                        "读取评测目录失败：" + path.toAbsolutePath() + " —— " + e.getMessage(), e);
            }
            if (files.isEmpty()) {
                throw new IllegalArgumentException(
                        "评测目录里没有 *.json：" + path.toAbsolutePath());
            }
            List<EvalSet> sets = new ArrayList<>(files.size());
            for (Path f : files) {
                sets.add(load(f));
            }
            return sets;
        }

        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("评测集路径不存在：" + path.toAbsolutePath());
        }
        return List.of(load(path));
    }
}
