package io.duo.sim.protocol;

/** Duo 线协议违规（魔数/版本/长度/报文语义非法），连接应立即关闭。 */
public class ProtocolViolationException extends RuntimeException {

    public ProtocolViolationException(String message) {
        super(message);
    }
}
