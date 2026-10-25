/**
 * 场景库（M10 计划一 Task 3）：{@link ScenarioLibrary}——内置模板（classpath
 * {@code /console-templates/}，只读）+ 用户库（{@code --library-dir}，可写）。id 白名单与
 * 「写入严格限定在 userDir 内」在这里强制；经 Web 上传的 YAML 一律按
 * {@code InputTrust.EXTERNAL_INPUT} 档全量校验（规则 1–11）——不因「存成了文件」而升档。
 */
package io.duo.sim.control.library;
