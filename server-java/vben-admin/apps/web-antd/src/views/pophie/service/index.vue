<script lang="ts" setup>
import type { PophieApi } from '#/api/pophie';

import { onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Button,
  Card,
  Descriptions,
  DescriptionsItem,
  message,
  Modal,
  Space,
  Switch,
  Tag,
} from 'ant-design-vue';

import { getServiceApi, restartServiceApi, setSpeechApi } from '#/api/pophie';

const loading = ref(false);
const speechSaving = ref(false);
const info = ref<PophieApi.ServiceInfo | null>(null);

async function load() {
  loading.value = true;
  try {
    info.value = await getServiceApi();
  } finally {
    loading.value = false;
  }
}

async function toggleSpeech(checked: boolean) {
  speechSaving.value = true;
  try {
    const res: any = await setSpeechApi(checked);
    message.success(`语音已${checked ? '开启' : '关闭'}`);
    if (info.value) {
      info.value.speech_config_enabled = res?.speech_config_enabled ?? checked;
      info.value.speech_runtime = res?.speech_runtime ?? checked;
    }
  } finally {
    speechSaving.value = false;
  }
}

function confirmRestart() {
  Modal.confirm({
    title: '重启服务',
    content: '将延迟约 600ms 后退出进程，由容器/守护进程自动拉起。确认重启？',
    okType: 'danger',
    async onOk() {
      await restartServiceApi();
      message.success('已触发重启，请稍候刷新');
    },
  });
}

onMounted(load);
</script>

<template>
  <Page title="服务管理" description="查看服务运行状态、开关语音功能、重启服务。">
    <Card :loading="loading" title="运行状态">
      <Descriptions v-if="info" :column="2" bordered size="small">
        <DescriptionsItem label="进程 PID">{{ info.pid }}</DescriptionsItem>
        <DescriptionsItem label="监听地址">
          {{ info.host }}:{{ info.port }}
        </DescriptionsItem>
        <DescriptionsItem label="配置语音开关">
          <Tag :color="info.speech_config_enabled ? 'green' : 'default'">
            {{ info.speech_config_enabled ? '已启用' : '已禁用' }}
          </Tag>
        </DescriptionsItem>
        <DescriptionsItem label="运行时语音">
          <Tag :color="info.speech_runtime ? 'green' : 'red'">
            {{ info.speech_runtime ? '可用' : '不可用' }}
          </Tag>
        </DescriptionsItem>
      </Descriptions>
    </Card>

    <Card class="mt-4" title="语音功能开关">
      <Space align="center">
        <Switch
          :checked="info?.speech_config_enabled ?? false"
          :loading="speechSaving"
          @change="(v) => toggleSpeech(Boolean(v))"
        />
        <span class="text-sm text-gray-500">
          切换配置中的语音启用状态（对应 speech.enabled）
        </span>
      </Space>
    </Card>

    <Card class="mt-4" title="服务操作">
      <Space>
        <Button :loading="loading" @click="load">刷新状态</Button>
        <Button danger type="primary" @click="confirmRestart">重启服务</Button>
      </Space>
    </Card>
  </Page>
</template>
