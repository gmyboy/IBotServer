<script lang="ts" setup>
import { ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Button,
  Empty,
  Input,
  InputNumber,
  message,
  Table,
  Tag,
} from 'ant-design-vue';

import { listConversationsApi } from '#/api/pophie';

const loading = ref(false);
const userId = ref('');
const sessionId = ref('');
const limit = ref<number>(100);
const rows = ref<any[]>([]);
const searched = ref(false);

const columns = [
  { title: '角色', key: 'role', width: 100 },
  { title: '内容', key: 'content' },
  { title: '会话 ID', dataIndex: 'session_id', key: 'session_id', width: 200 },
  { title: '时间', dataIndex: 'created_at', key: 'created_at', width: 180 },
];

function roleOf(record: any): string {
  return record.role ?? record.speaker ?? record.sender ?? '-';
}

function contentOf(record: any): string {
  return record.content ?? record.text ?? record.message ?? JSON.stringify(record);
}

function roleColor(role: string): string {
  if (role === 'user') return 'blue';
  if (role === 'assistant' || role === 'robot') return 'green';
  return 'default';
}

async function load() {
  if (!userId.value.trim()) {
    message.warning('请输入用户 ID');
    return;
  }
  loading.value = true;
  searched.value = true;
  try {
    const res = await listConversationsApi({
      userId: userId.value.trim(),
      sessionId: sessionId.value.trim() || undefined,
      limit: limit.value || 100,
    });
    rows.value = (res?.items ?? []).map((it: any, i: number) => ({
      ...it,
      _key: it.id ?? i,
    }));
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <Page title="对话历史" description="按用户查看对话历史（可按会话 ID 过滤）。">
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <Input
        v-model:value="userId"
        allow-clear
        placeholder="用户 ID（必填）"
        style="width: 200px"
        @press-enter="load"
      />
      <Input
        v-model:value="sessionId"
        allow-clear
        placeholder="会话 ID（可选）"
        style="width: 200px"
      />
      <InputNumber v-model:value="limit" :max="500" :min="1" :step="50" />
      <Button type="primary" @click="load">查询</Button>
    </div>

    <Table
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="{ pageSize: 20, showSizeChanger: true }"
      row-key="_key"
      size="middle"
    >
      <template #emptyText>
        <Empty :description="searched ? '暂无对话' : '请输入用户 ID 后查询'" />
      </template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'role'">
          <Tag :color="roleColor(roleOf(record))">{{ roleOf(record) }}</Tag>
        </template>
        <template v-else-if="column.key === 'content'">
          <div style="white-space: pre-wrap">{{ contentOf(record) }}</div>
        </template>
      </template>
    </Table>
  </Page>
</template>
