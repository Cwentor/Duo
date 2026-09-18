# duo-sim-protocol

**Duo 线协议**的帧格式、编解码与契约报文定义。

- 依赖：`jackson-databind`（**无内部模块依赖**——第三方协议适配器只需依赖本工件 + 内核公开 SPI，不依赖内核内部实现）
- 测试：12 条（`mvn -o -pl duo-sim-protocol test`）

## 关键类

| 类 | 职责 |
| --- | --- |
| `FrameCodec` | 帧布局 `[magic "DUO1": 4B][version: 1B][payloadLength: 4B][payload: NB]`（大端）；`HEADER_LENGTH=9`；单帧上限 1 MiB；魔数/版本/长度非法抛 `ProtocolViolationException` |
| `DuoCodec` | 报文 ⇄ JSON payload ⇄ 完整帧的门面（线程安全） |
| `DuoMessage` | 报文接口 + `@JsonSubTypes` 封闭清单（`type` 判别字段） |
| `message/` | `register`、`register-response`、`heartbeat`、`slot`、`task-dispatch`、`task-ack`、`task-status`、`task-cancel` |
| `FrameConnection` | 基于帧的双向连接：**读侧单线程**（并发读会互相偷帧）、**写侧多线程安全**（`write` 把「写入+flush」串行化，帧边界不被交错/截断——生产侧同一连接上有心跳/下行读/任务三个写者） |

## 边界

- 本协议是**框架自定义**的真实 TCP 协议，**不冒充**任何第三方产品协议。
- 交互型契约（`worker`/`engine`/`scheduler`）的 `virtual` 档暴露本协议端口；
  `scheduler` 契约的 M0 server 侧语义复用同一组报文。
- 第三方产品接入交互型契约 ＝ 为其写 Duo 帧翻译层（见 [路线图 M6](../docs/ROADMAP.md#m6--external-sut-与第三方接入)）。

→ [架构说明 · 线协议](../docs/ARCHITECTURE.md#13-duo-线协议3)
