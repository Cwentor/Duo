package io.duo.sim.kernel.api;

/** 组件启动/停止失败（§12：报告根因链，由组件管理器逆序拆除）。 */
public class ComponentException extends RuntimeException {

    /** 仅消息（无底层异常）。 */
    public ComponentException(String message) {
        super(message);
    }

    /** 消息 + 根因链（§12：报告完整根因，不只报最外层）。 */
    public ComponentException(String message, Throwable cause) {
        super(message, cause);
    }
}
