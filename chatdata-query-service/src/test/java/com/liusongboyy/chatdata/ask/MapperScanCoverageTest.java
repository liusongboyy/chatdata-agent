package com.liusongboyy.chatdata.ask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @MapperScan} 的覆盖测试：每个 {@code @Mapper} 接口都要被扫到。
 *
 * <h2>这个坑已经踩过两次</h2>
 *
 * <p>第一次是 {@code engine.model.mapper}（多模型），第二次是
 * {@code ask.report.mapper}（分析报告）。两次的症状一模一样：
 *
 * <pre>
 * No qualifying bean of type 'xxx.mapper.XxxMapper' available
 * </pre>
 *
 * <p>而报错指向的是**依赖它的 Service 的构造参数**，不是 {@code @MapperScan}。
 * 第一次排查花了十几分钟才想到是扫描范围。
 *
 * <p>注释拦不住人（{@code WebAppForwardController} 那个"新增路由必须补一行"
 * 已经证明过）。测试能。
 *
 * <h2>为什么扫 {@code com.liusongboyy.chatdata} 而不是只扫 ask 包</h2>
 *
 * <p>引擎层的 mapper 也在这个应用里注册（见 {@code @MapperScan} 的列表），
 * 只扫 ask 包会漏掉它们。
 */
class MapperScanCoverageTest {

    /** 根包。与 {@code @SpringBootApplication} 的 scanBasePackages 一致。 */
    private static final String ROOT = "com.liusongboyy.chatdata";

    @Test
    @DisplayName("★ 每个 @Mapper 接口都被 @MapperScan 覆盖——漏一个启动就失败")
    void everyMapperIsScanned() {
        Set<String> scanned = Arrays.stream(
                        AskApplication.class.getAnnotation(MapperScan.class).value())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        List<String> missing = new ArrayList<>();
        for (String mapperPackage : mapperPackages()) {
            // 扫描是按包匹配的：mapper 的包要么等于某个扫描项，
            // 要么在它的子包下（Spring 的扫描是递归的）
            boolean covered = scanned.stream().anyMatch(
                    p -> mapperPackage.equals(p) || mapperPackage.startsWith(p + "."));
            if (!covered) {
                missing.add(mapperPackage);
            }
        }

        assertThat(missing)
                .as("以下包里的 @Mapper 没被 @MapperScan 覆盖。"
                        + "症状是启动报 No qualifying bean，而报错指向依赖它的 Service")
                .isEmpty();
    }

    /**
     * 扫出所有带 {@code @Mapper} 的接口所在的包。
     *
     * <p>不用 {@code ClassPathScanningCandidateComponentProvider}：它按
     * class 文件扫，而测试运行时的 classpath 是多目录拼接的，
     * 那个扫描器经常一个都找不到（实测：返回空集，测试变成"永远通过"）。
     *
     * <p>改用**从已知的 mapper 包出发、列出 class 文件**的方式——
     * 直接读 {@code @MapperScan} 列表下每个包的文件系统。
     * 这样既可靠，又不会因为反射整个 classpath 而变慢。
     */
    private static Set<String> mapperPackages() {
        Set<String> out = new HashSet<>();
        // 从 jar/class 目录里列出 .class 文件
        for (String root : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            java.io.File f = new java.io.File(root);
            if (!f.exists()) {
                continue;
            }
            if (f.isDirectory()) {
                collectFromDir(new java.io.File(f, ROOT.replace('.', '/')), out);
            }
        }
        assertThat(out).as("一个 @Mapper 都没扫到，本测试失去意义").isNotEmpty();
        return out;
    }

    private static void collectFromDir(java.io.File dir, Set<String> out) {
        if (!dir.isDirectory()) {
            return;
        }
        java.io.File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (java.io.File f : files) {
            if (f.isDirectory()) {
                collectFromDir(f, out);
            } else if (f.getName().endsWith(".class")) {
                String rel = f.getAbsolutePath();
                int idx = rel.indexOf(ROOT.replace('.', java.io.File.separatorChar));
                if (idx < 0) {
                    continue;
                }
                String cls = rel.substring(idx, rel.length() - ".class".length())
                        .replace(java.io.File.separatorChar, '.');
                try {
                    Class<?> c = Class.forName(cls);
                    if (c.isAnnotationPresent(org.apache.ibatis.annotations.Mapper.class)) {
                        String pkg = cls.substring(0, cls.lastIndexOf('.'));
                        out.add(pkg);
                    }
                } catch (Throwable ignored) {
                    // 加载不了的类（依赖缺失等）跳过——它不可能是本项目能用的 mapper
                }
            }
        }
    }
}
