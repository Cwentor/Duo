# architecture-ui — Duo 交互式架构图

ReactFlow（@xyflow/react）实现的架构画布，部署在 GitHub Pages（仓库站点根路径），README 的
「交互式架构图」一节链接至此。图数据（节点描述/关键类）的唯一事实来源是各包
`package-info.java` 与 `docs/ARCHITECTURE.md`——改了架构先改那边，再同步这里。

与 `console-ui` 同纪律：构建期独立于 Maven reactor；产物不进仓库（dist 已 gitignore）。

## 本地开发

```bash
npm install
npm run dev        # http://localhost:5173
npm run build      # 产物在 dist/（base=/）
```

## 部署（GitHub Pages）

`.github/workflows/architecture-pages.yml` 在 push 到 master 且本目录有变更时：
`npm ci → npm run build -- --base=/Duo/ → deploy-pages`。首次启用需在仓库
Settings → Pages 把 Source 设为 **GitHub Actions**（一次手工开关）。

## 预览图再生成

README 的静态预览 `docs/assets/architecture-preview.png` 来自本画布 fitView 后的整页截图；
布局/配色变更后用浏览器打开 `npm run preview` 重截一张即可。
