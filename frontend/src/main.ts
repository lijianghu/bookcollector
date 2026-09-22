import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIcons from '@element-plus/icons-vue'

import 'element-plus/dist/index.css'
import '@/styles/index.css'

import App from './App.vue'
import router from './router'

const app = createApp(App)

/**
 * 全局注册所有 Element Plus 图标。
 *
 * 为什么不用按需引入：图标是「到处都要用一点」的东西（菜单、按钮、表格操作列、
 * 指标卡），按需引入要在每个文件里写一长串 import，收益只是省几十 KB ——
 * 而这是本地单机后台，首屏体积完全不敏感。
 *
 * ⚠️ 代价是**图标名写错不会报错**，只会渲染出一个空位。
 * 所以 `config/menu.ts` 里改 `icon` 之后要肉眼确认一眼侧边栏。
 * 用到的名字：Notebook / DataLine / Reading / List / Collection / Aim / Document /
 * Fold / Expand / Refresh / User / Lock / ArrowDown / SwitchButton
 */
Object.entries(ElementPlusIcons).forEach(([name, component]) => {
  app.component(name, component)
})

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
