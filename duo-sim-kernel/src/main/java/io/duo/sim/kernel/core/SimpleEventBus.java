package io.duo.sim.kernel.core;

import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.EventBus;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** 进程内轻量事件总线（§7.4）：订阅在发布线程同步执行。 */
public final class SimpleEventBus implements EventBus {

    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void subscribe(Consumer<Event> listener) {
        listeners.add(listener);
    }

    @Override
    public void publish(Event event) {
        for (Consumer<Event> l : listeners) {
            l.accept(event);
        }
    }
}
