package io.duo.sim.kernel.api;

/**
 * in-process SUT 入口契约（§7.3）。实现类经拓扑 {@code launch.main} 指定，
 * 内核在独立线程调用阻塞式 {@code run(SutContext)}——阻塞直至 SUT 退出；
 * 跑完即返回（M0 场景结束信号＝{@code sut.exited}）。
 */
public interface SutMain {

    void run(SutContext ctx) throws Exception;
}
