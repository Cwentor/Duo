// 产物合规门（规格 Review Focus #5）：CSP default-src 'self' 下不允许内联脚本与外域资源。
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'

const dist = resolve(import.meta.dirname, '../../duo-sim-control/target/classes/console')
const files = []
function walk(dir) {
  for (const f of readdirSync(dir)) {
    const p = join(dir, f)
    if (statSync(p).isDirectory()) walk(p)
    else files.push(p)
  }
}
walk(dist)

let failed = false
for (const p of files.filter((f) => f.endsWith('.html'))) {
  const html = readFileSync(p, 'utf8')
  if (/<script(?![^>]*src=)[^>]*>/.test(html)) {
    console.error(`FAIL ${p}: inline <script> violates CSP default-src 'self'`)
    failed = true
  }
}
for (const p of files) {
  const text = readFileSync(p, 'utf8')
  for (const m of text.matchAll(/(?:src|href)="(https?:)?\/\//g)) {
    console.error(`FAIL ${p}: remote resource reference ${m[0]}`)
    failed = true
  }
}
if (!readFileSync(join(dist, 'index.html'), 'utf8').includes('Duo Console')) {
  console.error('FAIL index.html missing Duo Console title')
  failed = true
}
if (failed) process.exit(1)
console.log(`dist check OK: ${files.length} files, CSP-compatible`)
