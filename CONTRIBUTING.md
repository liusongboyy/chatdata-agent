# 参与贡献

感谢你愿意花时间。这个项目对**报错要准确**的要求，比多数项目高一些——
因为它的失败形态是"数字不对但不报错"，所以文档和错误信息里多写了"为什么"。
那些不是废话，是防止后来人踩同一个坑。

## 开发环境

1. 装 **Java 21**：`java -version` 必须是 21（不是 17，更不是 8）
2. 起依赖：`docker compose up -d`
3. 按 [README 快速开始](README.md#快速开始) 建库与启动

**用 `java -jar` 启动，不要用 `mvn spring-boot:run`**：
后者会重新编译，而前端产物不在 Maven 的构建链里——改了前端容易忘记 build，
跑起来的是旧界面。原因与踩坑记录见 [docs/local-dev.md](docs/local-dev.md)。

## 提交前

```bash
mvn test                                    # 必须全过
npm --prefix chatdata-web run build   # 改了前端才需要
git status                                  # 确认构建产物一起提交了
```

### 前端产物必须提交

`chatdata-query-service/src/main/resources/static/**` 与
`chatdata-identity-service/src/main/resources/static/**` 是 `npm run build` 生成的，
但 **Maven 不会构建它**（没有 frontend-maven-plugin）。
所以这些文件要在 git 里，否则 clone 下来是白屏。

改动前端的顺序是：`npm run build` → 确认产物更新 → 一起提交。

## 代码风格

- 注释写**为什么**，不写**是什么**。`// 设置 name` 没有信息量；
  `// 用 COUNT(DISTINCT) 而不是 COUNT，因为这一列有重复` 有价值
- 遇到"看起来有点绕"的地方，写清它防的是哪种失败。这个项目里多数绕的代码
  都是为了防静默错误
- 不要为了通过测试改测试。测试红了先看是实现错了还是断言错了——
  判断依据写在测试的 `@DisplayName` 里

## 提 PR

- 一个 PR 一件事。混了重构和新功能的 PR 没法评审
- 说明**为什么**这么改，不只说改了什么
- 涉及权限、渲染器、DSL 的改动，请说明对这几条的影响：
  - 行级/列级权限是否仍然逐步注入
  - 是否可能产生"执行成功但结果不对"的情况
  - 评测基线是否需要重跑

## 报 Issue

请带上：

- 复现步骤（越短越好）
- 期望结果 vs 实际结果
- 如果涉及问数：**问题原文、生成的 SQL、`我理解成了` 那块的截图**
- 版本：`git log -1 --oneline`

如果是"答案不对"，最有价值的信息是**你的问题被理解成了什么**——
那能直接定位是语义建模的问题、还是模型理解的问题。

## 许可

提交的代码默认按 [Apache License 2.0](LICENSE) 授权。
