<script lang="ts" setup>
import type { PophieApi } from '#/api/pophie';

import { onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Button,
  Descriptions,
  DescriptionsItem,
  Drawer,
  Dropdown,
  Input,
  Menu,
  MenuItem,
  message,
  Modal,
  Popconfirm,
  Space,
  Table,
} from 'ant-design-vue';

import {
  deleteRobotApi,
  getRobotDetailApi,
  listRobotsApi,
  renameRobotApi,
  wipeApi,
} from '#/api/pophie';

const loading = ref(false);
const keyword = ref('');
const rows = ref<PophieApi.RobotRow[]>([]);

const columns = [
  { title: '机器人 ID', dataIndex: 'robot_id', key: 'robot_id' },
  { title: '名称', dataIndex: 'display_name', key: 'display_name' },
  { title: '记忆数', dataIndex: 'memories_count', key: 'memories_count', width: 100 },
  {
    title: '对话数',
    dataIndex: 'conversations_count',
    key: 'conversations_count',
    width: 100,
  },
  {
    title: '待提醒',
    dataIndex: 'reminders_pending',
    key: 'reminders_pending',
    width: 90,
  },
  { title: '最近活跃', dataIndex: 'last_seen_at', key: 'last_seen_at' },
  { title: '操作', key: 'action', width: 260 },
];

async function load() {
  loading.value = true;
  try {
    const res = await listRobotsApi(keyword.value.trim() || undefined);
    rows.value = res?.items ?? [];
  } finally {
    loading.value = false;
  }
}

/* ------- 重命名 ------- */
const renameOpen = ref(false);
const renameTarget = ref<PophieApi.RobotRow | null>(null);
const renameValue = ref('');

function openRename(row: PophieApi.RobotRow) {
  renameTarget.value = row;
  renameValue.value = row.display_name ?? '';
  renameOpen.value = true;
}

async function submitRename() {
  if (!renameTarget.value) return;
  await renameRobotApi(renameTarget.value.robot_id, renameValue.value.trim());
  message.success('已更新名称');
  renameOpen.value = false;
  await load();
}

/* ------- 删除 ------- */
async function removeRobot(row: PophieApi.RobotRow) {
  await deleteRobotApi(row.robot_id);
  message.success(`已删除机器人 ${row.robot_id}`);
  await load();
}

/* ------- 数据清除 ------- */
const scopeLabels: Record<PophieApi.WipeScope, string> = {
  memories: '记忆',
  conversations: '对话历史',
  reminders: '提醒',
  all: '全部数据',
};

function confirmWipe(row: PophieApi.RobotRow, scope: PophieApi.WipeScope) {
  Modal.confirm({
    title: `清除「${scopeLabels[scope]}」`,
    content: `确认清除机器人 ${row.robot_id} 的${scopeLabels[scope]}？此操作不可恢复。`,
    okType: 'danger',
    async onOk() {
      const res: any = await wipeApi(scope, row.robot_id);
      message.success(`已清除：${JSON.stringify(res?.deleted ?? {})}`);
      await load();
    },
  });
}

/* ------- 详情 ------- */
const detailOpen = ref(false);
const detailLoading = ref(false);
const detail = ref<PophieApi.RobotDetail | null>(null);

async function openDetail(row: PophieApi.RobotRow) {
  detailOpen.value = true;
  detailLoading.value = true;
  detail.value = null;
  try {
    detail.value = await getRobotDetailApi(row.robot_id);
  } finally {
    detailLoading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <Page title="机器人管理" description="管理所有机器人：查看统计、重命名、清除数据、删除。">
    <div class="mb-3 flex items-center gap-2">
      <Input
        v-model:value="keyword"
        allow-clear
        placeholder="搜索名称或机器人 ID"
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
      row-key="robot_id"
      size="middle"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'action'">
          <Space>
            <Button size="small" type="link" @click="openDetail(record)">
              详情
            </Button>
            <Button size="small" type="link" @click="openRename(record)">
              重命名
            </Button>
            <Dropdown>
              <Button size="small" type="link">清除数据</Button>
              <template #overlay>
                <Menu>
                  <MenuItem @click="confirmWipe(record, 'memories')">
                    清除记忆
                  </MenuItem>
                  <MenuItem @click="confirmWipe(record, 'conversations')">
                    清除对话
                  </MenuItem>
                  <MenuItem @click="confirmWipe(record, 'reminders')">
                    清除提醒
                  </MenuItem>
                  <MenuItem @click="confirmWipe(record, 'all')">
                    清除全部
                  </MenuItem>
                </Menu>
              </template>
            </Dropdown>
            <Popconfirm
              title="确认删除该机器人及全部关联数据？"
              ok-type="danger"
              @confirm="removeRobot(record)"
            >
              <Button danger size="small" type="link">删除</Button>
            </Popconfirm>
          </Space>
        </template>
      </template>
    </Table>

    <!-- 重命名 -->
    <Modal
      v-model:open="renameOpen"
      title="重命名机器人"
      @ok="submitRename"
    >
      <Input v-model:value="renameValue" placeholder="请输入新的显示名称" />
    </Modal>

    <!-- 详情 -->
    <Drawer
      v-model:open="detailOpen"
      title="机器人详情"
      width="480"
    >
      <div v-if="detailLoading">加载中…</div>
      <Descriptions v-else-if="detail" :column="1" bordered size="small">
        <DescriptionsItem label="机器人 ID">
          {{ detail.robot_id }}
        </DescriptionsItem>
        <DescriptionsItem label="名称">
          {{ detail.display_name ?? '-' }}
        </DescriptionsItem>
        <DescriptionsItem label="创建时间">
          {{ detail.created_at ?? '-' }}
        </DescriptionsItem>
        <DescriptionsItem label="最近活跃">
          {{ detail.last_seen_at ?? '-' }}
        </DescriptionsItem>
        <DescriptionsItem label="记忆数">
          {{ detail.memories_count }}
        </DescriptionsItem>
        <DescriptionsItem label="对话数">
          {{ detail.conversations_count }}
        </DescriptionsItem>
        <DescriptionsItem label="会话数">
          {{ detail.session_count }}
        </DescriptionsItem>
        <DescriptionsItem label="待提醒">
          {{ detail.reminders_pending }}
        </DescriptionsItem>
        <DescriptionsItem label="主动日志">
          {{ detail.proactive_log_count }}
        </DescriptionsItem>
        <DescriptionsItem label="分层记忆">
          {{ JSON.stringify(detail.layer_counts ?? {}) }}
        </DescriptionsItem>
      </Descriptions>
    </Drawer>
  </Page>
</template>
