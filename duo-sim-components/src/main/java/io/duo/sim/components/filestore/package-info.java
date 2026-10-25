/**
 * filestore 契约的 virtual 档（M5 交付物 1）：{@link VirtualFilestore}——共享存储替身
 * （进程内可控的临时目录，真 IO、真路径，不引 HDFS/S3 重依赖）。端点形态 FS_PATH +
 * 同进程门面；所有相对路径做越界校验（{@code ../} 逃逸显式拒绝），挂载丢失以
 * {@code supportedFaults={crash}} 表达。
 */
package io.duo.sim.components.filestore;
