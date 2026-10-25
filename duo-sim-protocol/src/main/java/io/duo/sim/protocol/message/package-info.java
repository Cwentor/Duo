/**
 * Duo 线协议报文集（T2）：上行 Register / Heartbeat / SlotReport / TaskAck / TaskStatus，
 * 下行 RegisterResponse / TaskDispatch / TaskCancel——覆盖「worker 拨号、单条双向长连接」
 * 连接模型（计划 §2）的全部往返。scheduler 契约的服务端语义复用同一组报文；
 * 报文封闭清单经 {@link io.duo.sim.protocol.DuoMessage} 的 {@code @JsonSubTypes} 显式注册
 * （非 sealed：报文在本子包而根类型在父包）。
 */
package io.duo.sim.protocol.message;
