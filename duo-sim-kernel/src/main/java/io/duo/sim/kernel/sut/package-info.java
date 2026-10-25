/**
 * SUT 启动面（§7.3 / M6）：in-process（{@link SutLauncher}——独立线程调阻塞 {@code run(SutContext)}）
 * 与 external（{@link ExternalSutLauncher}——端点告知双途径 + 就绪探针 + 进程退出竞速）两种形态；
 * 单次探测逻辑独立在 {@link ReadyProbe}（轮询与超时归 launcher 掌握）。
 * SUT 的业务契约（{@code SutMain}/{@code SutContext}）在 {@code kernel.api}。
 */
package io.duo.sim.kernel.sut;
