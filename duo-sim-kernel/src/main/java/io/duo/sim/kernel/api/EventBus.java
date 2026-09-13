package io.duo.sim.kernel.api;

import java.util.function.Consumer;

/** 进程内事件总线（§7.4）：轻量发布/订阅，订阅在发布线程同步执行。 */
public interface EventBus {

    void subscribe(Consumer<Event> listener);

    void publish(Event event);
}
