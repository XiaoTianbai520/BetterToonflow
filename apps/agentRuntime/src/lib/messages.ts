import { z } from "zod";
import { zhCN } from "zod/locales";

const validationLocale = zhCN();
z.config(validationLocale);

export function translateMessage(message: string) { return message; }

export function translateError(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}

export function t(strings: TemplateStringsArray, ...values: unknown[]): string {
  return strings.reduce((message, part, index) => message + part + (index < values.length ? String(values[index]) : ""), "");
}

export function validationOptions() { return { error: validationLocale.localeError }; }
