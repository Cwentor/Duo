/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// build.outDir 直指 duo-sim-control 的 classpath 资源目录：
// 先 npm build 再 mvnw —— maven-resources 缺省不覆盖更新的目标文件，占位页不反冲 SPA。
export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: '../duo-sim-control/target/classes/console',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      // dev 模式转发到本机 serve；生产同源无需 proxy
      '/api': 'http://127.0.0.1:7788',
      '/scenario': 'http://127.0.0.1:7788',
      '/events': 'http://127.0.0.1:7788',
      '/inject': 'http://127.0.0.1:7788',
      '/topology': 'http://127.0.0.1:7788',
      '/diagnose': 'http://127.0.0.1:7788',
      '/metrics': 'http://127.0.0.1:7788',
      '/health': 'http://127.0.0.1:7788',
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
