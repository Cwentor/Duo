package io.duo.sim.kernel.api;

import java.util.Map;

/**
 * SUT 事实发布门面（§7.3）：SUT 把内部关键事实（任务终态、重试、转移、选主）
 * 发布为 {@code sut.} 前缀事件。内核不解释语义、仅转发与录制。
 */
public interface SutEventPublisher {

    void publish(String type, Map<String, Object> payload);
}
