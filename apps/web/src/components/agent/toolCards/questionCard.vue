<template>
  <el-card class="questionCard" shadow="never" @keydown.stop @keyup.stop>
    <div class="questionHeader">
      <icon-message-question :size="18" />
      <span class="questionTitle">{{ title }}</span>
      <el-tag size="small" :type="answer && !skipped ? 'success' : 'info'">{{ statusText }}</el-tag>
    </div>
    <template v-if="tool.status === 'error'">
      <p class="questionText">表单暂时未生成，请 AI 重新整理。</p>
      <details v-if="toolContent" class="errorDetails">
        <summary>查看错误详情</summary>
        <pre class="errorText">{{ toolContent }}</pre>
      </details>
    </template>
    <p v-else-if="question" class="questionText">{{ question }}</p>
    <template v-if="waiting">
      <el-text v-if="formError" type="danger" role="alert">{{ formError }}</el-text>
      <form-create v-else-if="formRules.length" v-model="formValues" v-model:api="formApi" :rule="formRules" :option="formOptions" />
      <template v-else>
        <el-radio-group v-if="options.length" v-model="selected" class="questionOptions" :disabled="submitting" :aria-label="question">
          <el-radio v-for="option in options" :key="option" :value="option" border>{{ option }}</el-radio>
          <el-radio value="" border>自行填写</el-radio>
        </el-radio-group>
        <el-input
          v-model="text"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 6 }"
          :disabled="submitting"
          :maxlength="8000"
          :placeholder="options.length ? '也可以直接回答或补充说明' : '输入你的回答'"
          aria-label="回答问题" />
        <el-text v-if="draftAnswer.length > 8000" type="danger">回答（含选项）不能超过 8000 字</el-text>
      </template>
      <div class="questionActions">
        <el-button type="primary" :loading="submitting" :disabled="!directory || !!formError || (formRules.length ? !formApi : !draftAnswer || draftAnswer.length > 8000)" @click="submitAnswer(false)">
          提交回答
        </el-button>
        <el-button :disabled="!directory || submitting" @click="submitAnswer(true)">跳过</el-button>
      </div>
    </template>
    <p v-else-if="answer" class="answerText">{{ answer }}</p>
  </el-card>
</template>

<script setup lang="ts">
import { computed, ref, shallowRef, watch } from "vue";
import axios from "axios";
import formCreate, { type Api, type Options, type Rule } from "@/components/settings/formCreate";
import { ElCard, ElTag, ElButton, ElText, ElMessage, ElInput, ElRadio, ElRadioGroup } from "element-plus";
import "element-plus/es/components/base/style/css";
import "element-plus/es/components/card/style/css";
import "element-plus/es/components/tag/style/css";
import "element-plus/es/components/button/style/css";
import "element-plus/es/components/text/style/css";
import "element-plus/es/components/input/style/css";
import "element-plus/es/components/radio/style/css";
import "element-plus/es/components/radio-group/style/css";
import { IconMessageQuestion } from "@tabler/icons-vue";
import type { ToolCall } from "./runtime";

const props = defineProps<{ tool: ToolCall; directory?: string }>();
const toolArgs = computed(() => props.tool.args ?? props.tool.arguments ?? {});
const toolContent = computed(() => props.tool.result ?? props.tool.content ?? "");
const completed = computed(() => ["success", "completed"].includes(props.tool.status));
const title = computed(() => props.tool.question?.title || (typeof toolArgs.value.title === "string" ? toolArgs.value.title.trim() : "") || "请确认");
const selected = ref("");
const text = ref("");
const submitting = ref(false);
const submittedAnswer = ref("");
const submittedSkipped = ref(false);
const formApi = shallowRef<Api>();
const formValues = ref<Record<string, unknown>>({});
watch(() => [props.tool.id, props.tool.question?.callId, props.directory], () => {
  selected.value = "";
  text.value = "";
  submittedAnswer.value = "";
  submittedSkipped.value = false;
  submitting.value = false;
  formValues.value = {};
  formApi.value?.resetFields();
});
const formOptions = computed<Options>(() => ({ form: { labelPosition: "top", disabled: submitting.value }, submitBtn: false, resetBtn: false }));
const formError = computed(() => (props.tool.question?.fields?.length ?? 0) > 12 ? "单次提问最多支持 12 个字段，请重新生成表单。" : "");
const formRules = computed<Rule[]>(() => (formError.value ? [] : props.tool.question?.fields ?? []).map(field => ({
  type: field.type === "textarea" ? "input" : field.type,
  field: field.field,
  title: field.title,
  value: field.type === "checkbox" ? [] : field.type === "switch" ? false : field.type === "inputNumber" ? undefined : "",
  props: {
    placeholder: field.placeholder,
    ...(field.type === "textarea" ? { type: "textarea", autosize: { minRows: 2, maxRows: 6 } } : {}),
    ...(["input", "textarea"].includes(field.type) ? { maxlength: 8000 } : {}),
    ...(field.type === "select" ? { clearable: true } : {}),
  },
  options: field.options?.map(value => ({ label: value, value })),
  validate: field.required ? [{
    required: true,
    type: field.type === "checkbox" ? "array" : field.type === "inputNumber" ? "number" : field.type === "switch" ? "boolean" : "string",
    message: `请填写${field.title}`,
    ...(field.type === "checkbox" ? { min: 1 } : {}),
    ...(["input", "textarea"].includes(field.type) ? { whitespace: true } : {}),
  }] : [],
})));
const question = computed(() => props.tool.question?.question ?? (typeof toolArgs.value.question === "string" ? toolArgs.value.question : ""));
const options = computed(() => {
  const values = props.tool.question?.options ?? toolArgs.value.options;
  return Array.isArray(values) ? [...new Set(values.filter((value): value is string => typeof value === "string" && value.trim().length > 0))] : [];
});
const toolResult = computed(() => {
  if (!completed.value || !toolContent.value) return;
  try {
    return JSON.parse(toolContent.value) as { answer?: unknown; skipped?: boolean };
  } catch { return; }
});
const skipped = computed(() => submittedSkipped.value || toolResult.value?.skipped === true);
const answer = computed(() => {
  if (submittedAnswer.value) return submittedAnswer.value;
  if (!completed.value) return "";
  return typeof toolResult.value?.answer === "string" ? toolResult.value.answer : toolContent.value;
});
const waiting = computed(() => props.tool.status === "running" && !!props.tool.question?.callId && !answer.value);
const draftAnswer = computed(() => [selected.value, text.value.trim()].filter(Boolean).join("\n"));
const statusText = computed(() => {
  if (props.tool.status === "error") return "待重新生成";
  if (skipped.value) return "已跳过";
  if (answer.value) return "已回答";
  if (props.tool.status === "interrupted") return "已停止";
  return waiting.value ? "等待回答" : "提问记录";
});

async function submitAnswer(skip: boolean) {
  const callId = props.tool.question?.callId;
  const directory = props.directory;
  const value = draftAnswer.value;
  if (!waiting.value || submitting.value || !props.directory || !callId) return;
  if (!skip && (formError.value || !formRules.value.length && (!value || value.length > 8000))) return;
  submitting.value = true;
  try {
    if (!skip && formRules.value.length && !(await formApi.value?.validate().catch(() => false))) return;
    if (props.tool.question?.callId !== callId || props.directory !== directory) return;
    const response = await axios.post("/api/agent/answer", {
      directory,
      callId,
      ...(skip ? { skipped: true } : formRules.value.length ? { values: formApi.value!.formData() } : { answer: value }),
    }, { headers: { "x-toonflow-workspace": "1" } });
    if (props.tool.question?.callId !== callId || props.directory !== directory) return;
    if (response.data.code !== 200) throw new Error(response.data.message || "提交回答失败");
    submittedAnswer.value = response.data.data.answer;
    submittedSkipped.value = response.data.data.skipped === true;
  } catch (error) {
    if (props.tool.question?.callId !== callId || props.directory !== directory) return;
    const message = axios.isAxiosError(error) ? error.response?.data?.message : undefined;
    ElMessage.error(message || (error instanceof Error ? error.message : "提交回答失败"));
  } finally {
    if (props.tool.question?.callId === callId && props.directory === directory) submitting.value = false;
  }
}
</script>

<style scoped lang="scss">
.questionCard {
  .questionHeader {
    display: flex;
    align-items: center;
    gap: 8px;

    .questionTitle {
      min-width: 0;
      overflow-wrap: anywhere;
    }

    .el-tag {
      margin-left: auto;
      flex-shrink: 0;
    }
  }

  .questionText,
  .answerText,
  .errorText {
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  }

  .errorDetails {
    color: var(--el-text-color-secondary);
    font-size: 12px;

    summary {
      cursor: pointer;
    }

    .errorText {
      max-height: 240px;
      margin-bottom: 0;
      overflow: auto;
      font-family: var(--el-font-family);
    }
  }

  .questionOptions {
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
    margin-bottom: 12px;

    .el-radio {
      width: 100%;
      height: auto;
      margin: 0;
      padding: 8px 12px;

      :deep(.el-radio__label) {
        white-space: normal;
        overflow-wrap: anywhere;
      }
    }
  }

  .questionActions {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-top: 12px;

    .el-button {
      margin: 0;
    }
  }
}
</style>
