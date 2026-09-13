package io.duo.sim.kernel.api;

/** 组件启动/停止失败（§12：报告根因链，由组件管理器逆序拆除）。 */
public class ComponentException extends RuntimeException {

    public ComponentException(String message) {
        super(message);
    }

    public ComponentException(String message, Throwable cause) {
        super(message, cause);
    }
}
