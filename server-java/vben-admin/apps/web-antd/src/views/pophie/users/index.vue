<script lang="ts" setup>
import type { PophieApi } from '#/api/pophie';

import { onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Button,
  Descriptions,
  DescriptionsItem,
  Drawer,
  Input,
  Space,
  Table,
  Tag,
} from 'ant-design-vue';

import { getUserDetailApi, listUsersApi } from '#/api/pophie';

const loading = ref(false);
const keyword = ref('');
const rows = ref<PophieApi.UserRow[]>([]);

const columns = [
  { title: '用户 ID', dataIndex: 'user_id', key: 'user_id' },
  { title: '昵称', dataIndex: 'nickname', key: 'nickname' },
  { title: '显示名', dataIndex: 'display_name', key: 'display_name' },
  { title: '性别', dataIndex: 'gender', key: 'gender', width: 80 },
  { title: '声纹', key: 'voice', width: 90 },
  { title: '设备数', dataIndex: 'devices_count', key: 'devices_count', width: 90 },
  { title: '最近活跃', dataIndex: 'last_seen_at', key: 'last_seen_at' },
  { title: '操作', key: 'action', width: 100 },
];

async function load() {
  loading.value = true;
  try {
    const res = await listUsersApi(keyword.value.trim() || undefined);
    rows.value = res?.items ?? [];
  } finally {
    loading.value = false;
  }
}

const detailOpen = ref(false);
const detailLoading = ref(false);
const detail = ref<Record<string, any> | null>(null);

async function openDetail(row: PophieApi.UserRow) {
  detailOpen.value = true;
  detailLoading.value = true;
  detail.value = null;
  try {
    detail.value = await getUserDetailApi(row.user_id);
  } finally {
    detailLoading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <Page title="用户管理" description="查看所有用户及其绑定设备/机器人。">
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
      :pagination="{ pageSize: 10, showSizeChanger: true }"
      row-key="user_id"
      size="middle"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'voice'">
          <Tag :color="record.voice_enrolled ? 'green' : 'default'">
            {{ record.voice_enrolled ? '已录入' : '未录入' }}
          </Tag>
        </template>
        <template v-else-if="column.key === 'action'">
          <Button size="small" type="link" @click="openDetail(record)">
            详情
          </Button>
        </template>
      </template>
    </Table>

    <Drawer v-model:open="detailOpen" title="用户详情" width="480">
      <div v-if="detailLoading">加载中…</div>
      <template v-else-if="detail">
        <Descriptions :column="1" bordered size="small">
          <DescriptionsItem label="用户 ID">
            {{ detail.user_id }}
          </DescriptionsItem>
          <DescriptionsItem label="昵称">
            {{ detail.nickname ?? '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="显示名">
            {{ detail.display_name ?? '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="性别">
            {{ detail.gender ?? '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="生日">
            {{ detail.birthday ?? '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="声纹">
            {{ detail.voice_enrolled ? '已录入' : '未录入' }}
          </DescriptionsItem>
          <DescriptionsItem label="创建时间">
            {{ detail.created_at ?? '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="更新时间">
            {{ detail.updated_at ?? '-' }}
          </DescriptionsItem>
        </Descriptions>
        <div class="mt-4 mb-2 font-medium">绑定机器人/设备</div>
        <pre class="rounded bg-gray-50 p-2 text-xs dark:bg-gray-800">{{
          JSON.stringify(detail.devices ?? [], null, 2)
        }}</pre>
      </template>
    </Drawer>
  </Page>
</template>
