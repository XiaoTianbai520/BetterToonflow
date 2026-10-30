// 节点菜单契约：设置面板的菜单编辑器与画布右键菜单共用同一份实现，不再从服务端类型包引入。
// 存储态（settings.general.nodeMenu）与编辑态（NodeMenuItem）分开，避免把 UI 字段写回设置。

export type NodeMenuItem = {
  id: string;
  label: string;
  type?: string;
  alias?: string;
  hidden: boolean;
  children?: NodeMenuItem[];
};

export type StoredNodeMenuItem =
  | string
  | { type: string; alias?: string; hidden?: true }
  | { id: string; label: string; children: StoredNodeMenuItem[]; hidden?: true };

export function normalizeNodeMenu(value: unknown): StoredNodeMenuItem[] {
  const ids = new Set<string>();
  function normalize(items: unknown, depth: number, hidden = false): StoredNodeMenuItem[] {
    if (!Array.isArray(items)) return [];
    return items.flatMap((item): StoredNodeMenuItem[] => {
      const type = typeof item === "string" ? item : item?.type;
      const itemHidden = hidden || item?.hidden === true;
      if (typeof type === "string") {
        if (!type.trim() || ids.has(type)) return [];
        ids.add(type);
        const alias = typeof item?.alias === "string" ? item.alias.trim() : "";
        return [alias || itemHidden ? { type, ...(alias ? { alias } : {}), ...(itemHidden ? { hidden: true as const } : {}) } : type];
      }
      if (typeof item?.id !== "string" || !item.id.startsWith("group:") || !item.id.slice(6)
        || typeof item.label !== "string" || !item.label.trim() || !Array.isArray(item.children)) return [];
      // ACT: 超过三级的旧配置提升子项，保留节点的隐藏状态；编辑器在移动时限制深度。
      const promote = depth >= 3 || ids.has(item.id);
      ids.add(item.id);
      const children = normalize(item.children, promote ? depth : depth + 1, promote && itemHidden);
      return promote ? children : [{ id: item.id, label: item.label.trim(), children, ...(itemHidden ? { hidden: true as const } : {}) }];
    });
  }
  return normalize(value, 1);
}

export function resolveNodeMenu(value: unknown, nodes: readonly { type: string; label: string }[]): NodeMenuItem[] {
  const remaining = new Map(nodes.map(node => [node.type, node]));
  function resolve(items: StoredNodeMenuItem[]): NodeMenuItem[] {
    return items.flatMap((item): NodeMenuItem[] => {
      if (typeof item !== "string" && "children" in item) {
        return [{ id: item.id, label: item.label, hidden: item.hidden === true, children: resolve(item.children) }];
      }
      const type = typeof item === "string" ? item : item.type;
      const node = remaining.get(type);
      if (!node) return [];
      remaining.delete(type);
      const config = typeof item === "string" ? undefined : item;
      return [{ id: `node:${type}`, type, label: config?.alias || node.label, hidden: config?.hidden === true, ...(config?.alias ? { alias: config.alias } : {}) }];
    });
  }
  // ACT: 未出现在已保存菜单里的节点按节点注册顺序补齐，保证新节点默认可见且顺序稳定。
  const result = resolve(normalizeNodeMenu(value));
  return [...result, ...[...remaining.values()].map(node => ({ id: `node:${node.type}`, type: node.type, label: node.label, hidden: false }))];
}

export function filterNodeMenu(items: readonly NodeMenuItem[], nodeTypes: ReadonlySet<string>): NodeMenuItem[] {
  return items.flatMap((item): NodeMenuItem[] => {
    if (item.hidden) return [];
    if (item.type) return nodeTypes.has(item.type) ? [{ ...item }] : [];
    const children = filterNodeMenu(item.children ?? [], nodeTypes);
    return children.length ? [{ ...item, children }] : [];
  });
}

export function serializeNodeMenu(items: readonly StoredNodeMenuItem[] | readonly NodeMenuItem[], types: ReadonlySet<string>): StoredNodeMenuItem[] {
  return items.flatMap((item): StoredNodeMenuItem[] => {
    if (typeof item !== "string" && "children" in item && item.children) {
      return [{ id: item.id, label: item.label, children: serializeNodeMenu(item.children, types), ...(item.hidden ? { hidden: true as const } : {}) }];
    }
    const type = typeof item === "string" ? item : "type" in item ? item.type : undefined;
    if (!type || !types.has(type)) return [];
    const config: { alias?: string; hidden?: boolean } = typeof item === "string" ? {} : item;
    return [config.alias || config.hidden ? { type, ...(config.alias ? { alias: config.alias } : {}), ...(config.hidden ? { hidden: true as const } : {}) } : type];
  });
}
