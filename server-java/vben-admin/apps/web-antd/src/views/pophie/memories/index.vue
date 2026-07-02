<script lang="ts" setup>
import { ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Button,
  Empty,
  Input,
  message,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
} from 'ant-design-vue';

import { deleteMemoryApi, listMemoriesApi } from '#/api/pophie';

const loading = ref(false);
const userId = ref('');
const robotId = ref('');
const layer = ref<string | undefined>(undefined);
const sessionId = ref('');
const resolvedUserId = ref('');
const rows = ref<any[]>([]);
const searched = ref(false);

const layerOptions = [
  { label: '全部', value: '' },
  { label: 'L2', value: 'L2' },
  { label: 'L3', value: 'L3' },
  { label: 'L4', value: 'L4' },
];

const columns = [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 90 },
  { title: '分层', dataIndex: 'layer', key: 'layer', width: 90 },
  { title: '内容', key: 'content' },
  { title: '创建时间', dataIndex: 'created_at', key: 'created_at', width: 180 },
  { title: '操作', key: 'action', width: 140 },
];

function contentOf(record: any): string {
  return (
    record.content ??
    record.text ??
    record.summary ??
    record.value ??
    JSON.stringify(record)
  );
}

async function load() {
  if (!userId.value.trim()) {
    message.warning('请输入用户 ID');
    return;
  }
  loading.value = true;
  searched.value = true;
  try {
    const res = await listMemoriesApi({
      userId: userId.value.trim(),
      layer: layer.value || undefined,
      sessionId: sessionId.value.trim() || undefined,
    });
    rows.value = res?.items ?? [];
    resolvedUserId.value = res?.user_id ?? userId.value.trim();
  } finally {
    loading.value = false;
  }
}

async function removeMemory(record: any) {
  await deleteMemoryApi(record.id, robotId.value.trim() || undefined);
  message.success(`已删除记忆 #${record.id}`);
  await load();
}

function viewRaw(record: any) {
  Modal.info({
    title: `记忆 #${record.id}`,
    width: 640,
    content: JSON.stringify(record, null, 2),
  });
}
</script>

<template>
  <Page title="记忆管理" description="按用户查看记忆并删除。记忆按用户维度存储。">
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <Input
        v-model:value="userId"
        allow-clear
        placeholder="用户 ID（必填）"
        style="width: 200px"
        @press-enter="load"
      />
      <Select
        v-model:value="layer"
        :options="layerOptions"
        placeholder="分层"
        style="width: 120px"
      />
      <Input
        v-model:value="sessionId"
        allow-clear
        placeholder="会话 ID（可选）"
        style="width: 200px"
      />
      <Input
        v-model:value="robotId"
        allow-clear
        placeholder="机器人 ID（删除校验，可选）"
        style="width: 220px"
      />
      <Button type="primary" @click="load">查询</Button>
    </div>

    <Table
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="{ pageSize: 10, showSizeChanger: true }"
      row-key="id"
      size="middle"
    >
      <template #emptyText>
        <Empty :description="searched ? '暂无记忆' : '请输入用户 ID 后查询'" />
      </template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'content'">
          <div class="line-clamp-2">{{ contentOf(record) }}</div>
        </template>
        <template v-else-if="column.key === 'action'">
          <Space>
            <Button size="small" type="link" @click="viewRaw(record)">
              查看
            </Button>
            <Popconfirm
              title="确认删除该记忆？"
              ok-type="danger"
              @confirm="removeMemory(record)"
            >
              <Button danger size="small" type="link">删除</Button>
            </Popconfirm>
          </Space>
        </template>
      </template>
    </Table>
  </Page>
</template>
