# API 文档：`memory.d.ts`

`memory.d.ts` 描述的是 `Tools.Memory` 命名空间，用于查询、创建、更新和组织记忆库内容，并管理记忆之间的链接关系。

## 作用

当前定义覆盖：

- 语义检索记忆。
- 通过标题读取具体记忆内容。
- 创建、更新、删除、移动记忆。
- 建立、查询、更新、删除记忆链接。

## 运行时入口

```ts
Tools.Memory
```

## 主要 API

### 记忆查询与读取

#### `query(query, folderPath?, threshold?, limit?, startTime?, endTime?)`

```ts
query(
  query: string,
  folderPath?: string,
  threshold?: number,
  limit?: number,
  startTime?: number,
  endTime?: number
): Promise<string>
```

说明：

- `threshold` 为语义相似度阈值，默认注释值为 `0.35`。
- `limit` 范围注释为 `1-20`，默认 `5`。
- `startTime` / `endTime` 是毫秒时间戳过滤条件。

#### `getByTitle(title, chunkIndex?, chunkRange?, query?)`

通过精确标题读取记忆；当目标是文档型记忆时，可结合：

- `chunkIndex?`
- `chunkRange?`，例如 `"3-7"`
- `query?`，用于在文档内部进一步检索

返回值是 `Promise<string>`。

### 创建与更新

#### `create(title, content, contentType?, source?, folderPath?, tags?)`

创建新记忆，默认注释值包括：

- `contentType` 默认 `text/plain`
- `source` 默认 `ai_created`
- `folderPath` 默认空字符串

返回值是 `Promise<string>`。

#### `update(oldTitle, updates?)`

```ts
update(oldTitle: string, updates?: {
  newTitle?,
  content?,
  contentType?,
  source?,
  credibility?,
  importance?,
  folderPath?,
  tags?
}): Promise<string>
```

### 删除与移动

#### `deleteMemory(title)`

删除单个记忆，返回 `Promise<string>`。

#### `move(targetFolderPath, titles?, sourceFolderPath?)`

批量移动记忆：

- `titles` 可传字符串数组。
- 也可传逗号分隔字符串。
- `sourceFolderPath` 为空字符串时表示未分类目录。

## 记忆链接 API

### `link(sourceTitle, targetTitle, linkType?, weight?, description?)`

创建记忆链接，返回 `MemoryLinkResultData`。

### `queryLinks(linkId?, sourceTitle?, targetTitle?, linkType?, limit?)`

查询链接，返回 `MemoryLinkQueryResultData`。

### `updateLink(linkId?, sourceTitle?, targetTitle?, linkType?, newLinkType?, weight?, description?)`

更新已有链接，返回 `MemoryLinkResultData`。

### `deleteLink(linkId?, sourceTitle?, targetTitle?, linkType?)`

删除链接，返回 `Promise<string>`。

## 返回值特点

`memory.d.ts` 有两类返回风格：

- 记忆主体操作多数返回 `Promise<string>`。
- 链接相关操作使用结构化结果：`MemoryLinkResultData`、`MemoryLinkQueryResultData`。

## 示例

### 语义查询

```ts
const result = await Tools.Memory.query(
  '最近关于网络请求的笔记',
  'dev/network',
  0.4,
  5
);
console.log(result);
```

### 创建记忆

```ts
await Tools.Memory.create(
  'OkHttp 使用记录',
  '记录了常用请求写法',
  'text/plain',
  'manual',
  'dev/http',
  'android,http'
);
```

### 更新记忆

```ts
await Tools.Memory.update('OkHttp 使用记录', {
  content: '补充了拦截器与超时配置说明',
  importance: 0.9,
  tags: 'android,http,okhttp'
});
```

### 创建记忆链接

```ts
const link = await Tools.Memory.link(
  'OkHttp 使用记录',
  '网络请求排错',
  'related',
  0.8,
  '两者都与 Android 网络层有关'
);
complete(link);
```

## 相关文件

- `examples/types/memory.d.ts`
- `examples/types/results.d.ts`
- `docs/package_dev/results.md`
