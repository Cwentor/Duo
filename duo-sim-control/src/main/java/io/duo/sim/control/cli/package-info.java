/**
 * CLI 主类（M3 T34）：{@link DuoCli}——run / serve / stop / inject / status / events / assert /
 * topology / metrics / diagnose 子命令，同进程直连（缺省）与 REST 客户端（{@code --url}）
 * 两种模式（决策 D2）；退出码 0＝成功/断言通过、1＝失败，供脚本化验收与 CI 使用。
 * 远程取数阶梯（printRemote / fetchJson / fetchText）唯一一份，在本类。
 */
package io.duo.sim.control.cli;
