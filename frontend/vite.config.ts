import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    host: '127.0.0.1',
    proxy: {
      // 前端所有 /api 请求代理到后端 8080，开发期不用处理跨域
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  // `npm run preview` 起的是**生产构建**（dist/）。
  // ⚠️ 它**不会**读上面的 server.proxy —— vite 把 dev 与 preview 的配置分开存放，
  // 不在这里再写一遍，预览时每个 /api 请求都会 404，`preview` 脚本等于废的。
  // （2026-09-23 发现：package.json 里一直有 preview 脚本，但从来没有配过 proxy。）
  preview: {
    port: 4173,
    host: '127.0.0.1',
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
})
