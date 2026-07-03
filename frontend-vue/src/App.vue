<template>
  <div class="app-container">
    <header>
      <div>
        <h1>🤖 AgentRunner - 自动化测试执行器</h1>
        <p>左侧配置任务，右侧实时查看 AI 思考过程和操作步骤。Selenium 将打开独立浏览器窗口执行操作。</p>
      </div>
    </header>

    <main>
      <section class="left-panel">
        <div class="config-card">
          <h2>️ 任务配置</h2>

          <div class="form-group">
            <label for="targetUrl">目标页面 URL</label>
            <input
                v-model="targetUrl"
                placeholder="例如：http://localhost:5173/login"
                type="url"
            />
          </div>

          <div class="form-group">
            <label for="taskDescription">任务描述（自然语言）</label>
            <textarea
                v-model="taskDescription"
                rows="6"
                placeholder="例如：打开登录页面，输入用户名 test，密码 123456，点击登录按钮"
            ></textarea>
          </div>

          <div class="form-row">
            <div class="form-group">
              <label for="browser">浏览器</label>
              <select v-model="browser">
                <option value="EDGE">Microsoft Edge</option>
                <option value="CHROME">Google Chrome</option>
              </select>
            </div>

            <div class="form-group checkbox-group">
              <input type="checkbox" id="visual" v-model="visual" />
              <label for="visual">可视化模式（显示浏览器窗口）</label>
            </div>
          </div>

          <button @click="submitTask" :disabled="isRunning">
            🚀 {{ isRunning ? '执行中...' : '执行测试' }}
          </button>

          <div v-if="taskId" class="task-id-display">
            <strong>任务 ID:</strong> {{ taskId }}
          </div>
        </div>
      </section>

      <section class="right-panel">
        <div class="chat-panel-full">
          <div class="chat-header">
            <h2> AI 思考过程</h2>
            <span v-if="status" class="status-badge" :class="statusClass">{{ statusText }}</span>
          </div>

          <div ref="chatContainer" class="chat-messages">
            <div v-if="steps.length === 0 && !isRunning" class="empty-state">
              <p>等待执行测试...</p>
            </div>

            <div v-else-if="isRunning && steps.length === 0" class="loading-state">
              <div class="spinner"></div>
              <p>正在生成测试计划并执行...</p>
            </div>

            <div v-else class="messages-list">
              <div
                  v-for="(step, index) in steps"
                  :key="index"
                  class="message-item"
                  :class="{ thinking: step.type === 'thinking', action: step.type === 'action', result: step.type === 'result', needsInput: step.type === 'needs_input', done: step.type === 'done', error: step.type === 'error' }"
              >
                <div class="message-icon">
                  <span v-if="step.type === 'thinking'">🧠</span>
                  <span v-else-if="step.type === 'action'">⚡</span>
                  <span v-else-if="step.type === 'result'">✅</span>
                  <span v-else-if="step.type === 'needs_input'">⏸️</span>
                  <span v-else-if="step.type === 'done'"></span>
                  <span v-else-if="step.type === 'error'">❌</span>
                </div>
                <div class="message-content">
                  <div class="message-header">
                    <span class="message-type">{{ formatMessageType(step.type) }}</span>
                    <span v-if="step.step" class="message-step">第 {{ step.step }} 步</span>
                  </div>
                  <div class="message-text">{{ step.content }}</div>
                  <div v-if="step.details" class="message-details">{{ step.details }}</div>
                </div>
              </div>
            </div>
          </div>

          <div v-if="isWaitingInput" class="waiting-input-card">
            <h3>⏸️ {{ needsInputPrompt || '需要您的输入' }}</h3>
            <p class="waiting-hint">浏览器窗口保持打开中，请输入信息后点击提交继续执行。</p>
            <textarea
                v-model="userInputText"
                rows="3"
                placeholder="例如：验证码是 Ab3d，或者输入其他补充信息..."
            ></textarea>
            <div class="waiting-buttons">
              <button @click="resumeTask" class="btn-resume">
                ▶️ 提交并继续
              </button>
              <button @click="cancelTask" class="btn-cancel">
                ❌ 取消任务
              </button>
            </div>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onUnmounted } from 'vue';
import axios from 'axios';

const targetUrl = ref('http://localhost:5173/login');
const taskDescription = ref('');
const visual = ref(true);
const browser = ref('EDGE');
const taskId = ref('');
const status = ref('');
const taskMessage = ref('');
const steps = ref([]);
const polling = ref(false);
const needsInputPrompt = ref('');
const userInputText = ref('');
const chatContainer = ref(null);
let pollInterval = null;
let eventSource = null;

const isRunning = computed(() => status.value === 'RUNNING' || status.value === 'PENDING');
const isWaitingInput = computed(() => status.value === 'WAITING_INPUT');

const statusClass = computed(() => {
  if (status.value === 'SUCCESS') return 'success';
  if (status.value === 'FAILED' || status.value === 'ERROR') return 'failed';
  if (status.value === 'WAITING_INPUT') return 'waiting';
  return 'running';
});

const statusText = computed(() => {
  const map = {
    'PENDING': ' 等待中',
    'RUNNING': '🔄 执行中',
    'SUCCESS': '✅ 成功',
    'FAILED': ' 失败',
    'ERROR': '️ 错误',
    'WAITING_INPUT': '⏸️ 等待输入'
  };
  return map[status.value] || status.value;
});

function formatMessageType(type) {
  const map = {
    'thinking': ' AI 思考',
    'action': '⚡ 执行操作',
    'result': '✅ 操作结果',
    'needs_input': '⏸️ 等待输入',
    'done': '🎉 完成',
    'error': '❌ 错误'
  };
  return map[type] || type;
}

async function submitTask() {
  if (!targetUrl.value || !taskDescription.value) {
    alert('请填写目标 URL 和任务描述');
    return;
  }

  taskId.value = '';
  status.value = 'PENDING';
  taskMessage.value = '正在生成测试计划...';
  steps.value = [];
  needsInputPrompt.value = '';
  userInputText.value = '';

  document.querySelector('.left-panel button').disabled = true;

  try {
    const response = await axios.post('/api/automation/tasks', {
      targetUrl: targetUrl.value,
      taskDescription: taskDescription.value,
      browser: browser.value,
      visual: visual.value
    });

    if (response.data.taskId) {
      taskId.value = response.data.taskId;
      connectToStream(taskId.value);
    } else {
      showError(response.data.message || '请求失败');
    }
  } catch (error) {
    console.error(error);
    showError(error.response?.data?.message || error.message || '网络错误');
  }
}

function connectToStream(taskId) {
  if (eventSource) {
    console.log('Closing existing SSE connection');
    eventSource.close();
  }

  console.log('Creating new SSE connection for task:', taskId);
  eventSource = new EventSource(`/api/automation/tasks/${taskId}/stream`);

  console.log('SSE connection established for task:', taskId);

  eventSource.addEventListener('thinking', (event) => {
    const data = JSON.parse(event.data);
    console.log('✅ Received thinking event:', data);
    addThinkingStep(data);
  });

  eventSource.addEventListener('action_start', (event) => {
    const data = JSON.parse(event.data);
    console.log('✅ Received action_start event:', data);
    addActionStep(data);
  });

  eventSource.addEventListener('action_complete', (event) => {
    const data = JSON.parse(event.data);
    console.log('✅ Received action_complete event:', data);
    updateLastStepResult(data);
  });

  eventSource.addEventListener('needs_input', (event) => {
    const data = JSON.parse(event.data);
    console.log('✅ Received needs_input event:', data);
    addNeedsInputStep(data);
  });

  eventSource.addEventListener('done', (event) => {
    const data = JSON.parse(event.data);
    console.log('✅ Received done event:', data);
    addDoneStep(data);
  });

  eventSource.addEventListener('error', (event) => {
    const data = JSON.parse(event.data);
    console.log('✅ Received error event:', data);
    addErrorStep(data);
  });

  eventSource.onerror = (err) => {
    console.error('❌ SSE connection error:', err);
    console.error('ReadyState:', eventSource.readyState);
    eventSource.close();
  };

  // 监听连接打开事件
  eventSource.onopen = () => {
    console.log('🔓 SSE connection opened successfully');
  };
}

function addThinkingStep(data) {
  const lastStep = steps.value[steps.value.length - 1];
  if (lastStep && lastStep.type === 'thinking' && lastStep.step === data.step) {
    lastStep.content += '\n' + data.content;
  } else {
    steps.value.push({
      type: 'thinking',
      step: data.step,
      content: data.content,
      timestamp: Date.now()
    });
  }
  scrollToBottom();
}

function addActionStep(data) {
  steps.value.push({
    type: 'action',
    step: data.step,
    content: data.content || `${data.action} [${data.index}]`,
    details: data.description,
    timestamp: Date.now()
  });
  scrollToBottom();
}

function updateLastStepResult(data) {
  const lastStep = steps.value[steps.value.length - 1];
  if (lastStep && lastStep.type === 'action') {
    lastStep.type = 'result';
    lastStep.content = data.content;
    lastStep.success = data.success;
  }
  scrollToBottom();
}

function addNeedsInputStep(data) {
  console.log('Adding needs_input step:', data);
  steps.value.push({
    type: 'needs_input',
    step: data.step,
    content: data.content,
    timestamp: Date.now()
  });
  status.value = 'WAITING_INPUT';
  needsInputPrompt.value = data.content;
  console.log('Current status:', status.value, 'needsInputPrompt:', needsInputPrompt.value);

  // 关闭当前的 SSE 连接，防止接收后续事件
  if (eventSource) {
    console.log('Closing SSE connection after needs_input');
    eventSource.close();
    eventSource = null;
  }

  scrollToBottom();
}

function addDoneStep(data) {
  steps.value.push({
    type: 'done',
    step: data.step,
    content: data.content,
    success: data.success,
    timestamp: Date.now()
  });
  status.value = data.success ? 'SUCCESS' : 'FAILED';
  taskMessage.value = data.content;
  stopPolling();
  if (eventSource) {
    eventSource.close();
  }
  document.querySelector('.left-panel button').disabled = false;
  scrollToBottom();
}

function addErrorStep(data) {
  steps.value.push({
    type: 'error',
    step: data.step,
    content: data.content,
    timestamp: Date.now()
  });
  status.value = 'ERROR';
  taskMessage.value = data.content;
  stopPolling();
  if (eventSource) {
    eventSource.close();
  }
  document.querySelector('.left-panel button').disabled = false;
  scrollToBottom();
}

function scrollToBottom() {
  nextTick(() => {
    if (chatContainer.value) {
      chatContainer.value.scrollTop = chatContainer.value.scrollHeight;
    }
  });
}

async function resumeTask() {
  if (!userInputText.value.trim()) {
    alert('请输入信息后再提交');
    return;
  }
  try {
    console.log('🔄 Starting resume process...');
    console.log('User input:', userInputText.value);

    // 先重新建立 SSE 连接，确保恢复执行时有有效的 emitter
    console.log(' Re-establishing SSE connection before resuming...');
    connectToStream(taskId.value);

    // 等待一小段时间让 SSE 连接建立完成
    await new Promise(resolve => setTimeout(resolve, 500));
    console.log('⏳ SSE connection should be ready now');

    // 然后提交用户输入
    console.log('📤 Calling resume API...');
    const response = await axios.post(`/api/automation/tasks/${taskId.value}/resume`, {
      input: userInputText.value
    });
    console.log('✅ Resume API response:', response.data);

    userInputText.value = '';
    status.value = 'RUNNING';
    taskMessage.value = '正在恢复执行...';
    needsInputPrompt.value = '';

    console.log(' Task resumed, waiting for SSE events...');
  } catch (error) {
    console.error(' Resume error:', error);
    alert(error.response?.data?.error || '恢复失败');
  }
}

async function cancelTask() {
  try {
    await axios.post(`/api/automation/tasks/${taskId.value}/cancel`);
    status.value = 'FAILED';
    taskMessage.value = '用户取消了任务';
    needsInputPrompt.value = '';
    stopPolling();
    if (eventSource) {
      eventSource.close();
    }
    document.querySelector('.left-panel button').disabled = false;
  } catch (error) {
    console.error('Cancel error:', error);
  }
}

function stopPolling() {
  if (pollInterval) {
    clearInterval(pollInterval);
    pollInterval = null;
  }
}

onUnmounted(() => {
  if (eventSource) {
    eventSource.close();
  }
  stopPolling();
});

function showError(message) {
  status.value = 'ERROR';
  taskMessage.value = message;
  steps.value = [{
    type: 'error',
    content: message,
    timestamp: Date.now()
  }];
  document.querySelector('.left-panel button').disabled = false;
}
</script>

<style>
:root {
  --bg: #f4f8fc;
  --panel: #ffffff;
  --accent: #667eea;
  --accent-dark: #764ba2;
  --text: #0f172a;
  --muted: #64748b;
  --success: #16a34a;
  --danger: #dc2626;
  --warning: #f59e0b;
}

* {
  box-sizing: border-box;
}

body {
  margin: 0;
  font-family: ui-sans-serif, system-ui, sans-serif;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  min-height: 100vh;
}

.app-container {
  min-height: 100vh;
  padding: 20px;
}

header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  color: white;
}

header h1 {
  margin: 0;
  font-size: 28px;
}

header p {
  margin: 4px 0 0;
  opacity: 0.9;
}

main {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
  min-height: calc(100vh - 160px);
}

.left-panel,
.right-panel {
  background: var(--panel);
  border-radius: 16px;
  padding: 24px;
  box-shadow: 0 16px 40px rgba(15, 23, 42, 0.15);
}

.config-card h2 {
  margin: 0 0 20px;
  color: var(--text);
  font-size: 20px;
}

.form-group {
  margin-bottom: 16px;
}

.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 16px;
}

label {
  display: block;
  margin-bottom: 8px;
  color: var(--text);
  font-weight: 500;
}

input[type="text"],
input[type="url"],
textarea,
select {
  width: 100%;
  padding: 12px;
  border: 2px solid #e0e0e0;
  border-radius: 8px;
  font-size: 14px;
  transition: all 0.3s;
}

input:focus,
textarea:focus,
select:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.1);
}

textarea {
  resize: vertical;
  font-family: inherit;
}

.checkbox-group {
  display: flex;
  align-items: center;
  gap: 8px;
}

.checkbox-group input[type="checkbox"] {
  width: auto;
  cursor: pointer;
}

.checkbox-group label {
  margin: 0;
}

button {
  width: 100%;
  padding: 14px;
  background: linear-gradient(135deg, var(--accent) 0%, var(--accent-dark) 100%);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.2s, box-shadow 0.2s;
}

button:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(102, 126, 234, 0.4);
}

button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.task-id-display {
  margin-top: 16px;
  padding: 12px;
  background: #f0f0f0;
  border-radius: 8px;
  font-size: 13px;
  word-break: break-all;
}

.chat-panel-full {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 280px);
}

.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: #f8fafc;
  border-bottom: 1px solid #e0e0e0;
}

.chat-header h2 {
  margin: 0;
  color: var(--text);
  font-size: 18px;
}

.status-badge {
  padding: 6px 12px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 600;
}

.status-badge.running {
  background: #fff3cd;
  color: #856404;
}

.status-badge.waiting {
  background: #fff3cd;
  color: #856404;
  animation: pulse 2s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #fafafa;
}

.empty-state,
.loading-state {
  text-align: center;
  padding: 40px;
  color: var(--muted);
}

.spinner {
  width: 40px;
  height: 40px;
  border: 4px solid #e0e0e0;
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin: 0 auto 16px;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.messages-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.message-item {
  display: flex;
  gap: 12px;
  padding: 12px;
  border-radius: 8px;
  background: white;
  border-left: 4px solid var(--accent);
  animation: fadeIn 0.3s ease-in;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(-10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.message-item.thinking {
  border-left-color: #8b5cf6;
  background: #f5f3ff;
}

.message-item.action {
  border-left-color: #f59e0b;
  background: #fef3c7;
}

.message-item.result {
  border-left-color: var(--success);
  background: #d1fae5;
}

.message-item.needsInput {
  border-left-color: var(--warning);
  background: #fef3c7;
}

.message-item.done {
  border-left-color: var(--success);
  background: #d1fae5;
}

.message-item.error {
  border-left-color: var(--danger);
  background: #fee2e2;
}

.message-icon {
  font-size: 24px;
  flex-shrink: 0;
}

.message-content {
  flex: 1;
  min-width: 0;
}

.message-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.message-type {
  font-weight: 600;
  color: var(--text);
  font-size: 13px;
}

.message-step {
  font-size: 11px;
  color: var(--muted);
  background: #f0f0f0;
  padding: 2px 6px;
  border-radius: 4px;
}

.message-text {
  font-size: 14px;
  color: var(--text);
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.message-details {
  font-size: 12px;
  color: var(--muted);
  margin-top: 4px;
  font-style: italic;
}

.waiting-input-card {
  background: #fffbeb;
  border: 2px solid var(--warning);
  border-radius: 12px;
  padding: 20px;
  margin-top: 16px;
}

.waiting-input-card h3 {
  margin: 0 0 8px;
  color: #92400e;
  font-size: 18px;
}

.waiting-hint {
  color: #78716c;
  font-size: 13px;
  margin: 0 0 12px;
}

.waiting-input-card textarea {
  width: 100%;
  padding: 12px;
  border: 2px solid #e5e7eb;
  border-radius: 8px;
  font-size: 14px;
  font-family: inherit;
  resize: vertical;
  margin-bottom: 12px;
}

.waiting-input-card textarea:focus {
  outline: none;
  border-color: var(--warning);
  box-shadow: 0 0 0 3px rgba(245, 158, 11, 0.15);
}

.waiting-buttons {
  display: flex;
  gap: 12px;
}

.btn-resume {
  flex: 1;
  padding: 10px;
  background: linear-gradient(135deg, #f59e0b, #d97706);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.2s;
}

.btn-resume:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(245, 158, 11, 0.4);
}

.btn-cancel {
  padding: 10px 20px;
  background: #fee2e2;
  color: var(--danger);
  border: 2px solid var(--danger);
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-cancel:hover {
  background: var(--danger);
  color: white;
}

@media (max-width: 1200px) {
  main {
    grid-template-columns: 1fr;
  }

  .chat-panel-full {
    height: auto;
    min-height: 500px;
  }
}
</style>