package io.duo.sim.protocol;

/** Duo 线协议违规（魔数/版本/长度/报文语义非法），连接应立即关闭。 */
public class ProtocolViolationException extends RuntimeException {

    /** 违规说明（消息里带看到的实际值，如 bad magic: 0x…）。 */
    public ProtocolViolationException(String message) {
        super(message);
    }
}
