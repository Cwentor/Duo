import ReactDOM from 'react-dom/client'
import App from './App'
import './app.css'

// 不用 StrictMode：双挂载会与 xyflow 的节点测量/fitView 时序叠加，产生「已渲染但 visibility:hidden」
// 的中间态（实测本页复现）——图查看器不需要它提供的开发期警告。
ReactDOM.createRoot(document.getElementById('root')!).render(<App />)
