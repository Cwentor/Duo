package io.duo.sim.kernel.api;

/**
 * count &gt; 1 组件的实例级操作 SPI（§7.2 可选能力）。index 从 1 开始；
 * 实例下标唯一来源＝{@code action.target().instanceIndex()}。
 */
public interface InstanceControl {

    void stopInstance(int index, StopMode mode);

    void restartInstance(int index);

    void injectOnInstance(FaultAction action);
}
