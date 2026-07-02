<script lang="ts" setup>
import type { PophieApi } from '#/api/pophie';

import { onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Alert,
  Button,
  Card,
  message,
  Space,
  Tag,
  Textarea,
} from 'ant-design-vue';

import { getConfigApi, updateConfigApi } from '#/api/pophie';

const loading = ref(false);
const saving = ref(false);
const configText = ref('');
const configPath = ref('');
const restartRequiredFor = ref<string[]>([]);
const ttsVoices = ref<any[]>([]);

function applyResult(res: PophieApi.ConfigResult) {
  configText.value = JSON.stringify(res?.config ?? {}, null, 2);
  configPath.value = res?.config_path ?? '';
  restartRequiredFor.value = res?.restart_required_for ?? [];
  ttsVoices.value = res?.tts_voices ?? [];
}

async function load() {
  loading.value = true;
  try {
    applyResult(await getConfigApi());
  } finally {
    loading.value = false;
  }
}

async function save() {
  let parsed: Record<string, any>;
  try {
    parsed = JSON.parse(configText.value);
  } catch {
    message.error('配置不是合法的 JSON，请检查后再保存');
    return;
  }
  saving.value = true;
  try {
    const res = await updateConfigApi(parsed);
    applyResult(res);
    message.success('配置已保存（深度合并，热生效）');
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <Page title="配置管理" description="查看并更新运行时配置（提交内容会与 config.yaml 深度合并、热生效）。">
    <Card :loading="loading">
      <div class="mb-3 flex flex-wrap items-center gap-2">
        <span class="text-sm text-gray-500">配置文件：{{ configPath || '-' }}</span>
        <template v-if="restartRequiredFor.length">
          <span class="text-sm text-gray-500">需重启字段：</span>
          <Tag v-for="k in restartRequiredFor" :key="k" color="orange">
            {{ k }}
          </Tag>
        </template>
      </div>

      <Alert
        class="mb-3"
        message="敏感字段（如 api_key）在展示时已脱敏；若不修改，请勿覆盖脱敏值（保存脱敏值会写回 ****）。"
        show-icon
        type="warning"
      />

      <Textarea
        v-model:value="configText"
        :auto-size="{ minRows: 20, maxRows: 40 }"
        spellcheck="false"
        style="font-family: monospace"
      />

      <div class="mt-3">
        <Space>
          <Button :loading="saving" type="primary" @click="save">保存</Button>
          <Button :loading="loading" @click="load">重新加载</Button>
        </Space>
      </div>
    </Card>

    <Card v-if="ttsVoices.length" class="mt-4" title="可用 TTS 音色">
      <pre class="text-xs">{{ JSON.stringify(ttsVoices, null, 2) }}</pre>
    </Card>
  </Page>
</template>
