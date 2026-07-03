<template>
  <div class="app-container">
    <header>
      <div>
        <h1>🤖 AgentRunner - 自动化测试执行器</h1>
        <p>左侧配置任务，右侧查看执行结果。Selenium 将打开独立浏览器窗口执行操作。</p>
      </div>
    </header>

    <main>
      <section class="left-panel">
        <div class="config-card">
          <h2>⚙️ 任务配置</h2>

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
        <div class="result-header">
          <h2>📊 执行结果</h2>
          <span v-if="status" class="status-badge" :class="statusClass">{{ statusText }}</span>
        </div>

        <div v-if="resultSummary" class="result-summary" :class="summaryClass">
          <strong>{{ resultSummary.title }}</strong><br/>
          <small>{{ resultSummary.message }}</small>
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

        <div v-if="browserInfo" class="browser-info">
          浏览器: {{ browserInfo.browser }} | 可视化: {{ browserInfo.visual ? '是' : '否' }}
        </div>

        <h3 class="steps-title">执行步骤</h3>

        <div v-if="steps.length === 0 && !isRunning" class="empty-state">
          <p>等待执行测试...</p>
        </div>

        <div v-else-if="isRunning && steps.length === 0" class="loading-state">
          <div class="spinner"></div>
          <p>正在生成测试计划并执行...</p>
        </div>

        <div v-else class="execution-log">
          <div
            v-for="(step, index) in steps"
            :key="index"
            class="log-entry"
            :class="{ success: step.success, error: !step.success }"
          >
            <div class="log-header">
              <span class="log-title">{{ index + 1 }}. {{ step.description || step.actionType }}</span>
              <span class="log-status" :class="{ success: step.success, failed: !step.success }">
                {{ step.success ? '✓ 成功' : '✗ 失败' }}
              </span>
            </div>

            <div v-if="step.details" class="log-details">
              详情: {{ step.details }}
            </div>

            <div v-if="step.errorMessage" class="log-details error-text">
              错误: {{ step.errorMessage }}
            </div>

            <div v-if="step.screenshotBase64 || step.screenshot" class="screenshot-container">
              <img :src="step.screenshotBase64 || step.screenshot" alt="步骤截图" />
            </div>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue';
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
let pollInterval = null;

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
    'PENDING': '⏳ 等待中',
    'RUNNING': '🔄 执行中',
    'SUCCESS': '✅ 成功',
    'FAILED': '❌ 失败',
    'ERROR': '⚠️ 错误',
    'WAITING_INPUT': '⏸️ 等待输入'
  };
  return map[status.value] || status.value;
});

const resultSummary = computed(() => {
  if (!taskMessage.value) return null;
  if (status.value === 'WAITING_INPUT') return null;
  return {
    title: status.value === 'SUCCESS' ? '✅ 测试通过' : '❌ 测试失败',
    message: taskMessage.value
  };
});

const summaryClass = computed(() => {
  return status.value === 'SUCCESS' ? 'success' : 'failed';
});

const browserInfo = computed(() => {
  if (!steps.value.length) return null;
  return {
    browser: browser.value === 'EDGE' ? 'Microsoft Edge' : 'Google Chrome',
    visual: visual.value
  };
});

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
      startPolling(taskId.value);
    } else {
      showError(response.data.message || '请求失败');
    }
  } catch (error) {
    console.error(error);
    showError(error.response?.data?.message || error.message || '网络错误');
  }
}

async function resumeTask() {
  if (!userInputText.value.trim()) {
    alert('请输入信息后再提交');
    return;
  }
  try {
    const response = await axios.post(`/api/automation/tasks/${taskId.value}/resume`, {
      input: userInputText.value
    });
    userInputText.value = '';
    status.value = 'RUNNING';
    taskMessage.value = '正在恢复执行...';
    startPolling(taskId.value);
  } catch (error) {
    console.error('Resume error:', error);
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
    document.querySelector('.left-panel button').disabled = false;
  } catch (error) {
    console.error('Cancel error:', error);
  }
}

function startPolling(id) {
  if (pollInterval) {
    clearInterval(pollInterval);
  }

  pollInterval = setInterval(async () => {
    try {
      const response = await axios.get(`/api/automation/tasks/${id}`);

      if (response.data) {
        const data = response.data;
        const executionResult = data.result || {};

        console.log('Polling response:', {
          status: data.status,
          statusType: typeof data.status,
          needsInputPrompt: data.needsInputPrompt,
          stepsCount: executionResult.steps?.length || 0
        });

        status.value = data.status || 'RUNNING';
        taskMessage.value = executionResult.message || '';
        steps.value = executionResult.steps || [];

        if (data.needsInputPrompt) {
          needsInputPrompt.value = data.needsInputPrompt;
        }

        if (data.status === 'WAITING_INPUT') {
          document.querySelector('.left-panel button').disabled = false;
        } else if (data.status === 'SUCCESS' || data.status === 'FAILED') {
          clearInterval(pollInterval);
          pollInterval = null;
          document.querySelector('.left-panel button').disabled = false;
        }
      }
    } catch (error) {
      console.error('Polling error:', error);
    }
  }, 1000);
}

function stopPolling() {
  if (pollInterval) {
    clearInterval(pollInterval);
    pollInterval = null;
  }
}

function showError(message) {
  status.value = 'ERROR';
  taskMessage.value = message;
  steps.value = [{
    description: '错误',
    success: false,
    details: message
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

.result-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.result-header h2 {
  margin: 0;
  color: var(--text);
  font-size: 20px;
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

.result-summary {
  padding: 16px;
  border-radius: 8px;
  margin-bottom: 16px;
}

.result-summary.success {
  background: #d4edda;
  border: 2px solid var(--success);
}

.result-summary.failed {
  background: #f8d7da;
  border: 2px solid var(--danger);
}

.browser-info {
  font-size: 12px;
  color: var(--muted);
  margin-bottom: 16px;
}

.steps-title {
  margin: 20px 0 12px;
  color: var(--text);
  font-size: 18px;
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

.execution-log {
  max-height: 500px;
  overflow-y: auto;
}

.log-entry {
  padding: 16px;
  margin-bottom: 12px;
  border-radius: 8px;
  background: #f9f9f9;
  border-left: 4px solid var(--accent);
}

.log-entry.success {
  border-left-color: var(--success);
}

.log-entry.error {
  border-left-color: var(--danger);
}

.log-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.log-title {
  font-weight: 600;
  color: var(--text);
}

.log-status {
  font-size: 12px;
  padding: 4px 8px;
  border-radius: 4px;
  font-weight: 600;
}

.log-status.success {
  background: #d4edda;
  color: #155724;
}

.log-status.failed {
  background: #f8d7da;
  color: #721c24;
}

.log-details {
  font-size: 13px;
  color: var(--muted);
  margin-top: 4px;
}

.error-text {
  color: var(--danger);
  font-weight: 500;
}

.screenshot-container {
  margin-top: 12px;
}

.screenshot-container img {
  max-width: 100%;
  border-radius: 8px;
  border: 2px solid #e0e0e0;
}

.waiting-input-card {
  background: #fffbeb;
  border: 2px solid var(--warning);
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 16px;
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
}
</style>