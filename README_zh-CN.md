# Clone Improved（clone改进）

[English](README.md) | [中文](README_zh-CN.md)

一个服务端 Fabric mod，为原版 `/clone` 增加可组合的移动/旋转/镜像变换、空气掩码与撤销/重做。
原版客户端无需安装任何东西即可使用。

## 环境要求

- Minecraft **1.19.4**、**1.21.10**、**1.21.11** 或 **26.2**（各版本 jar 见 Releases）
- Fabric Loader + Fabric API
- 权限等级 2（OP）——继承自原版 `/clone` 节点
- 仅服务端生效：装在服务端（或单人游戏）即可，玩家无需安装

## 命令

以下全部是原版 `/clone` 的扩展；原版语法（`replace|masked|filtered <filter>`、
`force|move|normal`、`from <dim>`、`to <dim>`、1.21.2+ 的 `strict`）完全保留。

### 变换链：`move` / `rotate` / `mirror`

```
/clone <起点> <终点> <目的地> <链> [force]
/clone from <源维度> <起点> <终点> [to <目标维度>] <目的地> <链> [force]

链 ::= [mask_begin|mask_end|mask_both] 变换+
变换 ::= move | rotate (cw|ccw|reverse) | mirror (x|z) <坐标>
```

- `move` **必须**恰好出现一次，`rotate`、`mirror` 各至多一次，按书写顺序依次应用。
  `rotate cw` = 俯视顺时针 90°（北→东），`ccw` = 逆时针，`reverse` = 180°（反向）。
- `move` 与 `rotate` 都以当前状态下西北角为准；`mirror x 1.0` 表示以世界坐标
  `x = 1.0` 的竖直平面为镜面（支持 0.5 步进）。不支持竖向旋转/镜像。
- 楼梯、铁轨、门、原木等方向相关方块状态会被正确旋转/镜像。
- 与原版模式同样可以组合：`/clone <起点> <终点> <目的地> masked move rotate cw`。

示例：

```
/clone 0 64 0 10 70 10 100 64 100 move                  # 等价原版 replace move
/clone 0 64 0 10 70 10 100 64 100 move rotate cw        # 移动后顺时针旋转 90°
/clone 0 64 0 10 70 10 100 64 100 mirror z 105.5 move   # 先镜像再移动
/clone 0 64 0 10 70 10 100 64 100 mask_begin move reverse force
/clone 0 64 0 10 70 10 100 64 100 mask_end rotate ccw move
```

### 空气掩码：`mask_begin` / `mask_end` / `mask_both`

这三个取代原版 `masked`（原版 `masked` 保留不变），可以单独使用，也可以放在变换链之前：

| 模式 | 复制内容 | 目标区校验 | 失败条件 |
|---|---|---|---|
| `mask_begin` | 仅复制非空气方块（≡ 原版 `masked`） | 无 | 复制数为 0 → `clone.failed` |
| `mask_end` | 全部复制（含空气） | 每个被覆盖位置必须是空气 | 任一位置非空气 → 整条命令失败、**零写入** |
| `mask_both` | 仅复制非空气方块 | 将被非空气方块覆盖的位置必须是空气 | 同上 |

mask 不影响 `move` 清空源区域的行为（与原版一致）。结构空位（structure void）不是空气，
`mask_begin` 会复制它——与原版 `masked` 行为一致。

### 撤销 / 重做

```
/clone undo [<玩家>] [confirm|cancel]
/clone redo [<玩家>] [confirm|cancel]
```

每次成功执行 `/clone` 都会记录命令原文、时间、执行者，以及所有受影响区域的完整前后快照——
包括整个目标区域、`move` 的源区域、被 mask 跳过的空气。

- `/clone undo` 提出撤销自己上一次 clone，显示命令、时间、区域和影响方块数，用
  `confirm` 确认或 `cancel` 取消。
- 若区域在此期间被修改过，提案会警告修改的方块数——确认将覆盖这些修改。
- `/clone undo <玩家>` 提出撤销**别人**的操作；对方在线会收到消息（并告知是谁提出的）。
  提案者和记录所有者都可以 `confirm`/`cancel`；目标玩家离线也可以提案。
- 撤销后可以继续撤销更早的记录；`/clone redo` 用同样的提案流程撤销"这次撤销"。
  执行新的 `/clone` 会清空自己的重做栈。
- 全服同时最多一个待确认提案；所有记录保存到服务端关闭为止。

## 兼容性

- **Reden**：无命令、Mixin、网络冲突。两个 mod 都会记录 `/clone` 的写入——对同一次
  clone 请二选一使用撤销，避免双重撤销语义混乱。
- **Undo Mod**（客户端按键撤销）：clone 的写入不是玩家交互，不会进入它的历史；它在我们
  区域内的修改会体现在"已修改 N 个方块"警告里。
- **gitmatica**：纯客户端，无交集。

## 配置

`config/clone-improved.json`：

| 键 | 默认 | 含义 |
|---|---|---|
| `maxRecordsPerPlayer` | 32 | 每位玩家保留的撤销记录数（超出从最旧驱逐） |
| `maxTotalMemoryMiB` | 64 | 快照的全局软内存预算 |

## 已知限制

- 计划刻（已排程的水流等）在纯平移 clone 时与原版一样复制，旋转/镜像的 clone 不复制；
  撤销记录同样不包含计划刻。
- 实体不会被 clone/移动（与原版一致），撤销也不恢复实体。
- 1.19.4 服务端拿不到客户端语言，undo/redo 消息只有英文；1.21.10+ 会自动选择中/英文。

## 构建

运行 Gradle 需要 JDK 17+（编译用的 17/21/25 工具链会自动获取）：

```
gradle build          # 构建全部版本节点
gradle :buildAndCollect
```

多版本由 [Stonecutter](https://stonecutter.kikugie.dev/) 管理；共享源码在
`src/main/java`，版本差异全部收敛在 `multiver/MultiversionHelpers`。

## 许可证

[WTFPL v2](LICENSE) — 想干嘛干嘛。
