import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 部署到 GitHub Pages（仓库站点 https://cwentor.github.io/Duo/）时用 --base=/Duo/ 覆盖；
// 本地构建缺省 '/'，vite preview / 截图脚本按根路径取资源。
export default defineConfig({
  plugins: [react()],
  base: '/',
})
