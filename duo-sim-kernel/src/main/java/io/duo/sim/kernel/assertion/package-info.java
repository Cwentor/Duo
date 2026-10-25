/**
 * 断言内核（M1 T20）：YAML {@code assertions} 节的解析（{@link AssertionParser}）与实现集
 * （{@link Assertions}，语义按设计文档 §11 钉死：起点事件、成功判据、窗口边界逐条写在实现上）。
 * YAML 内置评估与 duo-sim-junit 的编程式断言共用同一 {@link Assertion} 接口与同一事件事实源
 * （§11 双轨分工）。
 */
package io.duo.sim.kernel.assertion;
