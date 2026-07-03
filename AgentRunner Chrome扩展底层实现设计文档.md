# AgentRunner Chrome扩展底层实现设计文档

## 文档概述

文档版本：V1\.0
扩展版本：1\.8\.2
开发规范：Chrome Extension Manifest V3
项目定位：AI 驱动浏览器自动化助手，支持自然语言下发网页操作指令，提供外部程序 WebSocket 接入、网页录制、网页单文件保存、AI 对话导出等配套能力。

## 一、整体架构总览

### 1\.1 分层架构结构

采用**服务工作线程中心化调度**架构，分为四层：外部交互层、核心调度层、页面注入层、目标网页层，所有指令、事件统一由 background\.js（Service Worker）中转分发。

1. 外部交互层：SidePanel 侧边栏、Hub 前端页面、MainWorld 全局 API、外部[localhost](https://localhost)客户端

2. 核心调度层：background\.js（唯一消息中枢、权限管理、标签事件监听、模块调度）

3. 页面注入层：content\.js 核心脚本、SingleFile 注入脚本、网页录制脚本、AI 对话导出脚本

4. 目标网页层：普通网页、iframe、ShadowDOM 节点

### 1\.2 架构拓扑

```Plain Text
Hub UI(hub.html) / SidePanel / window.PAGE_AGENT_EXT
        ↓ ↑（消息/端口通信）
Background.js Service Worker 【调度中心】
        ↓ ↑（chrome.tabs.sendMessage / scripting注入）
content.js + 各类功能子模块
        ↓ ↑（DOM操作、页面事件、MAIN WORLD通信）
目标网页页面（含iframe、ShadowDOM）
```

## 二、核心组件详细设计

### 2\.1 Background\.js 后台服务工作线程（调度中枢）

#### 2\.1\.1 核心职责

1. 全量消息路由、分类转发；

2. 全局标签页生命周期事件监听与广播；

3. 外部程序连接鉴权、WebSocket Hub 通信管理；

4. 用户认证 Token 持久化存储与校验；

5. SingleFile、网页录制、AI 导出等子模块统一调度；

6. 扩展全局状态、存储数据管理。

#### 2\.1\.2 消息分发处理逻辑

1. 页面控制消息：`handlePageControlMessage()`，转发自动化操作指令至 content\.js；

2. 标签管理消息：`handleTabControlMessage()`，处理标签新建、关闭、分组、切换；

3. 录制模块消息：`handleWebRecorderMessage()`，管理操作录制、截图存储；

4. 单文件保存：`initSingleFileListener()`，触发网页完整 HTML 打包下载。

#### 2\.1\.3 端口与外部连接机制

1. 标签事件广播端口

```js
setupTabEventsPort() {
  chrome.runtime.onConnect.addListener((port) => {
    if (port.name !== "tab-events") return;
    tabEventPorts.add(port);
    监听tabs.onCreated/onRemoved/onUpdated，全端口广播标签事件
  });
}
```

2. 外部跨域消息监听
仅允许[localhost](https://localhost)客户端接入，匹配 manifest 配置`externally_connectable: ["http://localhost/*"]`

```js
chrome.runtime.onMessageExternal.addListener((message, sender, sendResponse) => {
  if (message.type === "OPEN_HUB") {
    openOrFocusHubTab(message.wsPort);
  }
});
```

#### 2\.1\.4 鉴权存储设计

本地持久化存储密钥`PageAgentExtUserAuthToken`，所有外部调用、跨页面请求均校验该 Token，防止未授权调用。

### 2\.2 content\.js 页面注入脚本（DOM 自动化执行层）

注入至全部网页（\<all\_urls\>），运行在 ISOLATED 隔离世界，是页面操作执行载体。

#### 2\.2\.1 三大核心能力

1. DOM 树解析模块
移植 browser\-use 元素提取算法，实现能力：

- 全页面可交互元素映射标记；

- iframe、ShadowDOM 递归穿透解析；

- 自动识别滚动容器、可点击输入控件；

- 元素高亮可视化标记。

2. 标准浏览器行为模拟器
严格遵循 W3C 鼠标 / 指针事件完整流程，兼容 Vue/React 框架：

- 点击模拟：pointerover→mouseover→pointerdown→focus→pointerup→click 完整事件链；

- 文本输入：调用原生 valueSetter 赋值，派发 input 事件触发前端框架状态更新；

- 页面滚动：横向 / 纵向像素级滚动控制。

3. AI 可视化光标交互
内置专属光标 CSS 样式，层级置顶（z\-index:10000），通过页面自定义事件接收坐标指令：

```js
window.dispatchEvent(new CustomEvent("PageAgent::MovePointerTo", { 
  detail: { x, y } 
}));
```

#### 2\.2\.2 消息处理

监听 background 下发的`PAGE_CONTROL`指令，解析操作类型、目标元素、参数，调用 DOM 执行函数，执行完成后回调返回结果。

### 2\.3 main\-world\.js 页面原生全局 API（第三方调用入口）

注入网页 MAIN 主世界，对页面 JS 开放可编程接口，无隔离限制，供页面脚本直接调用自动化能力。

#### 2\.3\.1 全局挂载对象

```js
window.PAGE_AGENT_EXT = {
  version: "1.8.2",
  execute, // 执行自然语言自动化任务
  stop     // 强制终止当前运行任务
};
```

#### 2\.3\.2 通信流程

1. 外部调用`execute(task, config)`；

2. 通过 window\.postMessage 向 content 脚本发送跨世界请求，信道标识`PAGE_AGENT_EXT_REQUEST`；

3. 监听页面 message 事件接收执行结果，封装 Promise 同步返回；

#### 2\.3\.3 配置参数定义

|参数|作用|
|---|---|
|baseURL|AI 大模型接口服务地址|
|model|指定调用 AI 模型名称|
|apiKey|接口鉴权密钥|
|systemInstruction|AI 任务系统提示词|
|includeInitialTab|是否带入当前标签页上下文|
|onStatusChange/onActivity/onHistoryUpdate|任务生命周期回调|

### 2\.4 hub\.html WebSocket 外部集成面板

面向本地第三方程序，提供 WebSocket 长连接通道，实现外部程序远程下发自动化任务。

#### 2\.4\.1 WebSocket 通信类 HubWs

1. 连接本地指定端口 ws 服务；

2. 接收外部`execute`任务指令，转发至扩展内部执行；

3. 捕获执行结果，封装 message 返回客户端；

#### 2\.4\.2 安全控制

首次建立本地连接需用户手动授权，授权状态持久化存储于 chrome\.storage\.local，支持永久放行本地连接。

### 2\.5 配套功能子模块

#### 2\.5\.1 SingleFile 网页单文件保存模块

多阶段分帧脚本注入机制，完整提取页面资源打包为独立 HTML：

1. 阶段 1：全 iframe 注入帧前置脚本；

2. 阶段 2：主框架引导脚本；

3. 阶段 3：MAIN 世界钩子脚本，捕获页面资源；

4. 阶段 4：主页面内容处理脚本；
执行完成调用 chrome\.downloads API 本地下载文件。

#### 2\.5\.2 Web Recorder 操作录制模块

1. 捕获标签页可见区域截图（PNG 格式）；

2. 按执行顺序存储操作步骤、截图、操作描述；

3. 支持配置 GitHub 云端上传通道。

#### 2\.5\.3 AI Chat Exporter AI 对话导出模块

1. 动态注入 markdown 转换工具 turndown、选择器规则、生成器脚本；

2. 匹配各大 AI 对话页面 DOM 选择器，提取问答对话；

3. 将对话数据转换标准 Markdown 文本，支持复制 / 下载。

### 2\.6 SidePanel\.html 侧边栏 UI

扩展原生侧边交互面板，内置指令输入框、任务日志、操作配置面板，与 background 通过端口通信下发任务，实时展示自动化运行状态。

## 三、统一消息通信协议

### 3\.1 内部扩展消息类型（runtime/tabs 通信）

|消息标识|使用场景|
|---|---|
|PAGE\_CONTROL|页面自动化操作指令|
|TAB\_CONTROL|标签页管理、切换、分组|
|SINGLEFILE\_SAVE|触发网页单文件保存流程|

### 3\.2 跨世界页面通信（content ↔ main world）

通信信道标记：`PAGE_AGENT_EXT_REQUEST` / `PAGE_AGENT_EXT_RESPONSE`，使用 window\.postMessage 实现隔离世界数据互通。

### 3\.3 外部客户端通信

仅放行[localhost](https://localhost)域名外部请求，通过 runtime\.onMessageExternal 接收远程指令，鉴权后转发内部调度。

## 四、关键技术实现特性

1. Manifest V3 标准适配

- 使用 Service Worker 替代老式后台页面；

- 废弃 executeScript，统一使用 chrome\.scripting 动态注入脚本；

- 隔离存储、权限、网络请求新规范。

2. 多运行世界隔离设计

- ISOLATED 世界：content\.js，安全隔离页面原生 JS，防止变量污染、页面篡改；

- MAIN 世界：main\-world\.js，向业务页面开放可控 API。

3. 全页面兼容能力
自动递归解析 iframe、ShadowDOM，支持嵌套复杂网页结构；操作逻辑兼容 React/Vue 等主流前端框架。

4. 可视化反馈体系
AI 虚拟光标、可交互元素高亮、操作遮罩层，直观展示自动化执行动作。

## 五、扩展权限清单

### 5\.1 API 权限 permissions

```json
[
  "tabs",           // 标签基础信息、切换
  "tabGroups",      // 标签分组管理
  "sidePanel",      // 侧边栏面板能力
  "storage",        // 本地持久化存储
  "unlimitedStorage", // 无上限存储
  "downloads",      // 文件下载（SingleFile/导出对话）
  "scripting",      // 动态注入各类功能脚本
  "debugger",       // 页面调试辅助
  "webRequest",     // 网络请求拦截读取
  "webNavigation"   // 页面导航事件监听
]
```

### 5\.2 主机权限 host\_permissions

```json
["<all_urls>"] // 全部网页注入content脚本，实现全站点自动化
```

## 六、标准业务执行流程

1. 用户 / 外部程序输入自然语言自动化任务；

2. 入口分发（SidePanel / Hub WebSocket /window\.PAGE\_AGENT\_EXT）；

3. 消息转发至 background 服务工作线程；

4. background 校验权限、路由消息，发送至目标标签 content\.js；

5. content 脚本解析 DOM，定位操作目标元素；

6. 模拟原生鼠标 / 键盘行为执行页面操作；

7. 捕获页面执行结果、状态、截图，原路回调返回上层；

8. UI / 外部客户端展示任务执行日志与结果。

## 七、项目设计亮点总结

1. 中心化调度架构：所有流量收敛至 background，统一管控权限、状态、模块调度，降低多组件通信复杂度；

2. 双层 API 开放体系：内部侧边栏 UI \+ 外部页面全局 API \+ 本地 WebSocket 三层接入渠道，适配人机、程序远程两类使用场景；

3. 标准化浏览器自动化内核：复刻完整原生交互事件，兼容现代前端框架，DOM 解析支持 ShadowDOM/iframe 复杂页面；

4. 模块化功能解耦：录制、网页保存、对话导出为独立可插拔子模块，由后台统一调度；

5. 安全隔离机制：多运行世界隔离、外部连接白名单、全局 Token 鉴权，平衡开放能力与页面安全。

> （注：部分内容可能由 AI 生成）
