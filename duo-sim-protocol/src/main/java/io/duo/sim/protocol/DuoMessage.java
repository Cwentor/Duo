package io.duo.sim.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.duo.sim.protocol.message.RegisterRequest;
import io.duo.sim.protocol.message.RegisterResponse;
import io.duo.sim.protocol.message.HeartbeatReport;
import io.duo.sim.protocol.message.SlotReport;
import io.duo.sim.protocol.message.TaskDispatch;
import io.duo.sim.protocol.message.TaskAck;
import io.duo.sim.protocol.message.TaskStatus;
import io.duo.sim.protocol.message.TaskCancel;

/**
 * worker 契约报文集（T2）。scheduler 契约的 M0 server 侧语义复用同一组报文。
 *
 * <p>连接模型（计划 §2）：worker 拨号 master，每实例一条双向长连接——
 * 上行（worker → master）：Register/Heartbeat/SlotReport/TaskAck/TaskStatus；
 * 下行（master → worker）：TaskDispatch/TaskCancel。
 *
 * <p>非密封接口：sealed 要求直接子类同包，而报文在 message 子包，故用
 * {@code @JsonSubTypes} 显式注册类型集合（等价于"封闭报文清单"）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RegisterRequest.class, name = "register"),
        @JsonSubTypes.Type(value = RegisterResponse.class, name = "register-response"),
        @JsonSubTypes.Type(value = HeartbeatReport.class, name = "heartbeat"),
        @JsonSubTypes.Type(value = SlotReport.class, name = "slot"),
        @JsonSubTypes.Type(value = TaskDispatch.class, name = "task-dispatch"),
        @JsonSubTypes.Type(value = TaskAck.class, name = "task-ack"),
        @JsonSubTypes.Type(value = TaskStatus.class, name = "task-status"),
        @JsonSubTypes.Type(value = TaskCancel.class, name = "task-cancel"),
})
public interface DuoMessage {
}
