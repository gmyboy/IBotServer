<script lang="ts" setup>
import type { PophieApi } from '#/api/pophie';

import { computed, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Button,
  Input,
  message,
  Modal,
  Table,
  Tag,
} from 'ant-design-vue';

import { listUsersApi, pushMessageApi } from '#/api/pophie';

const loading = ref(false);
const keyword = ref('');
const rows = ref<PophieApi.UserRow[]>([]);

function robotIdsOf(row: PophieApi.UserRow | null | undefined): string[] {
  if (!row) return [];
  const ids = (row as any).robot_ids;
  return Array.isArray(ids) ? ids : [];
}

const columns = [
  { title: '用户 ID', dataIndex: 'user_id', key: 'user_id' },
  { title: '昵称', dataIndex: 'nickname', key: 'nickname' },
  { title: '显示名', dataIndex: 'display_name', key: 'display_name' },
  { title: '绑定机器人', key: 'robots' },
  { title: '设备数', dataIndex: 'devices_count', key: 'devices_count', width: 80 },
  { title: '最近活跃', dataIndex: 'last_seen_at', key: 'last_seen_at' },
  { title: '操作', key: 'action', width: 120 },
];

async function load() {
  loading.value = true;
  try {
    const res: any = await listUsersApi(keyword.value.trim() || undefined);
    const items = res?.items;
    rows.value = Array.isArray(items) ? items : [];
  } catch (e: any) {
    message.error(e?.message ?? '加载用户列表失败');
    rows.value = [];
  } finally {
    loading.value = false;
  }
}

const pushModalOpen = ref(false);
const pushTarget = ref<PophieApi.UserRow | null>(null);
const pushText = ref('');
const pushSending = ref(false);

const pushTargetRobots = computed(() => robotIdsOf(pushTarget.value));

function openPush(row: PophieApi.UserRow) {
  pushTarget.value = { ...row };
  pushText.value = '';
  pushModalOpen.value = true;
}

function cancelPush() {
  if (pushSending.value) return;
  pushModalOpen.value = false;
  pushTarget.value = null;
}

async function doPush() {
  if (!pushTarget.value) return;
  const text = pushText.value.trim();
  if (!text) {
    message.warning('请输入消息内容');
    return;
  }
  pushSending.value = true;
  try {
    const res: any = await pushMessageApi({
      user_id: pushTarget.value.user_id,
      text,
    });
    const targets = Array.isArray(res?.targets) ? res.targets : [];
    message.success(`已向 ${targets.length || 1} 个目标推送成功`);
    pushModalOpen.value = false;
    pushText.value = '';
  } catch (e: any) {
    message.error(e?.message ?? '推送失败');
  } finally {
    pushSending.value = false;
  }
}

function displayName(row: PophieApi.UserRow | null | undefined): string {
  if (!row) return '';
  return row.nickname || row.display_name || row.user_id;
}

onMounted(load);
</script>

<template>
  <Page title="消息推送" description="选择用户，向其绑定的机器人推送语音消息。">
    <div class="mb-3 flex items-center gap-2">
      <Input
        v-model:value="keyword"
        allow-clear
        placeholder="搜索昵称或用户 ID"
        style="width: 260px"
        @press-enter="load"
      />
      <Button type="primary" @click="load">搜索</Button>
      <Button @click="() => { keyword = ''; load(); }">重置</Button>
    </div>

    <Table
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="{ pageSize: 12, showSizeChanger: true }"
      row-key="user_id"
      size="middle"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'robots'">
          <template v-if="robotIdsOf(record).length">
            <Tag v-for="rid in robotIdsOf(record)" :key="rid" color="cyan">{{ rid }}</Tag>
          </template>
          <Tag v-else color="default">无</Tag>
        </template>
        <template v-else-if="column.key === 'action'">
          <Button size="small" type="primary" @click="openPush(record)">
            推送消息
          </Button>
        </template>
      </template>
    </Table>

    <Modal
      v-model:open="pushModalOpen"
      :confirm-loading="pushSending"
      cancel-text="取消"
      ok-text="发送"
      title="推送消息"
      width="480px"
      @cancel="cancelPush"
      @ok="doPush"
    >
      <div v-if="pushTarget" class="mb-3">
        <div class="mb-1 text-sm text-gray-500">推送目标：</div>
        <div class="flex flex-wrap items-center gap-1">
          <Tag color="blue">{{ displayName(pushTarget) }}</Tag>
          <span class="text-xs text-gray-400">{{ pushTarget.user_id }}</span>
        </div>
        <div class="mt-1 flex flex-wrap items-center gap-1">
          <span class="text-xs text-gray-500">机器人：</span>
          <template v-if="pushTargetRobots.length">
            <Tag v-for="rid in pushTargetRobots" :key="rid" color="cyan">{{ rid }}</Tag>
          </template>
          <Tag v-else color="default">default</Tag>
        </div>
      </div>
      <Input.TextArea
        v-model:value="pushText"
        :rows="4"
        placeholder="输入要播报的消息内容，客户端将通过TTS语音播报…"
        :disabled="pushSending"
      />
      <div class="mt-2 text-xs text-gray-400">
        提示：用户在线直接推送TTS；离线时入队并通过MQTT唤醒，上线后自动播报。
      </div>
    </Modal>
  </Page>
</template>
