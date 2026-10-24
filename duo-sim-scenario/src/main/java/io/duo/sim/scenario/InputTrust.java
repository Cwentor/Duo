package io.duo.sim.scenario;

/**
 * 输入来源的信任档（安全审计 2026-09-20，决策 D10/D11）——**全链路唯一一枚**：
 * 控制面（ScenarioHost/RestControlServer/ScenarioLibrary）、编排引擎（ScenarioEngine）
 * 与校验器（ScenarioValidator 规则 9–11）直接传递本类型，不再有镜像枚举与手工映射
 * （架构体检卡 6：此前 Trust/InputPolicy/InputTrust 三枚镜像，加一档要改三处枚举
 * +两处映射，且值名都不一致——EXTERNAL 与 EXTERNAL_INPUT 指同一档）。
 *
 * <p>{@link #CONFIG}＝场景 YAML 来自本机文件/classpath/CLI/测试：与「启动一个进程」同级，
 * 校验按**配置**对待，能力不缩水；{@link #EXTERNAL_INPUT}＝控制面把外部输入（REST body、
 * Web 上传的场景库条目）显式降级：禁止派生外部进程、框架外主类、config 键白名单、规模有界。
 *
 * <p>没有从外部输入设置档位的入口（环境属性只在本地测试/嵌入式里用）——档位只能由
 * **载入路径**决定，不能由输入自称。
 */
public enum InputTrust {
    CONFIG, EXTERNAL_INPUT
}
