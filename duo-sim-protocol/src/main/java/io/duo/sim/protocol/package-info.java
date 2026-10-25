/**
 * Duo 线协议（设计文档 §3）：master 与 worker 之间的自定义 TCP 帧——帧结构、长连接读写与报文编解码。
 * 交互型契约（worker/scheduler/engine）的 virtual 与 real 档都 speak 这一组帧；
 * 第三方适配器只需要依赖本工件（见模块 README）。
 *
 * <p>本包是帧层：{@link FrameCodec}（帧 layout 与编解码）、{@link FrameConnection}
 * （双向长连接上的帧读写，帧边界即协议边界）、{@link DuoCodec}（报文 ⇄ 帧门面）与报文根类型
 * {@link DuoMessage}；报文清单在 {@code protocol.message} 子包。
 */
package io.duo.sim.protocol;
