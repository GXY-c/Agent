# AgentRunner Vue 前端

这是一个 Vue 3 子项目，用于展示：

- 左侧自动打开并展示用户提供的目标页面 URL
- 右侧自然语言输入面板
- 提交后调用后端 `/api/automation/tasks` 接口
- 轮询任务状态并展示当前步骤、执行结果与截图
- 支持 `visual=true` 模式，模拟可视化执行

## 运行方法

1. 进入项目目录：
   ```bash
   cd frontend-vue
   ```
2. 安装依赖：
   ```bash
   npm install
   ```
3. 启动开发服务：
   ```bash
   npm run dev
   ```
4. 打开浏览器访问：
   ```text
   http://localhost:5173
   ```

## 使用说明

- 如果后端服务运行在 `http://localhost:8080`，Vite 已配置代理，前端可直接使用 `/api/automation` 调用。
- 在左侧输入要打开的目标页面 URL，例如 `http://localhost:8000/login.html`。
- 在右侧输入自然语言任务，如 `输入用户名密码并点击登录，然后检查是否登录成功`。
- 点击 `提交任务`，后端会调用大模型生成自动化步骤并执行。

- 在左侧输入要打开的目标页面 URL，例如 `http://localhost:8000/login.html`
- 在右侧输入自然语言任务，如 `输入用户名密码并点击登录，检查是否登录成功`
- 点击 `提交任务`，后端会调用大模型生成自动化步骤并执行
- 右侧将显示任务状态、步骤执行结果和截图

## 本地演示页面

已经新增本地演示页面：`frontend-demo/login.html`

你可以用任意静态服务器启动它，例如：

```bash
cd frontend-demo
npx http-server -p 8000
```

然后在 Vue 前端中填写 `http://localhost:8000/login.html` 作为目标 URL。
