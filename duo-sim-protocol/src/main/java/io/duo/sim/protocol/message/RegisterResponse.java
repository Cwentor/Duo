package io.duo.sim.protocol.message;

import io.duo.sim.protocol.DuoMessage;

/** 下行：注册响应（accepted=false 时携带原因，worker 应关闭连接）。 */
public record RegisterResponse(boolean accepted, String reason) implements DuoMessage {

    /** 受理成功（无原因串）。 */
    public static RegisterResponse ok() {
        return new RegisterResponse(true, null);
    }
}
