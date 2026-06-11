# Goat 開發規格

## 目的

Goat 是一個 Minecraft 1.21.11 的 Fabric 模組。這份文件用來讓開發者、使用者與協助開發的 agent 都能理解模組要做什麼、目前已有什麼、下一步應該怎麼擴充。

本文盡量使用玩家與設計層面的語言描述，不依賴程式專有名詞。

## 目前狀態

目前模組已經可以被 Minecraft 載入。

目前也已經有一個可開啟、可拖曳、可調整大小的 Goat GUI。GUI 使用自訂渲染的 TrueType 字體，不再使用 Minecraft 內建像素字體。

## 目前開發進度紀錄

紀錄日期：2026-06-10

目前已完成：

- Goat GUI 基礎互動已完成。
- Client module 基底系統已建立。
- GUI 會直接讀取 `ModuleManager` 中註冊的 module，不再為單一功能手寫同步邏輯。
- `AutoSprint` 已實作為 Movement module。
- `FullBright` 已實作為 Render module。
- `CustomFOV` 已實作為 Render module。
- `ModuleManager` 已依分類整理註冊流程。
- Goat client config 系統已建立，module 開關與設定值會保存到檔案。
- **FarmingMacro** 實作了無干涉按鍵綁定 (KeyBinding) 模擬。
- **Failsafe Manager** 建立了模組化的防查緝系統，具備視角與維度變更的監聽能力。
- **A* Pathfinder** 引擎完成，支援 1.21.11 `VoxelShape`，具備平滑轉向、卡死重算、以及粒子 ESP 路徑視覺化。

目前主要程式結構：

```text
src/client/java/com/justingoat/goat/client/module
```

其中：

- `GoatModule` 是所有功能的基底。
- `ModuleCategory` 定義 GUI 分類。
- `ModuleManager` 統一註冊與 tick 所有 module。
- `module/value` 放 module 設定值類型。
- `module/movement` 放移動類功能。
- `module/render` 放畫面類功能。

目前已通過檢查：

```text
.\gradlew.bat build
```

尚未完成或尚未確認：

- 尚未做完整遊戲內手動測試。
- 部分 GUI 中顯示的 module 仍是展示用 placeholder，尚未有實際遊戲功能。

啟動遊戲後，進入世界並輸入：

```text
/goat
```

如果聊天欄出現：

```text
Goat mod is loaded.
```

代表模組載入成功。

## 基本資料

模組名稱：Goat

模組識別名稱：goat

支援版本：Minecraft 1.21.11

使用環境：Fabric

開發環境：Gradle

Java 需求：Java 21

主要輸出檔案位置：

```text
build/libs/goat-1.0.0.jar
```

## 設計方向

Goat 的初始目標是成為一個可逐步擴充的 Minecraft 模組。所有新內容都應該先從清楚、可測試的小功能開始，再慢慢加入更大的玩法系統。

新增內容時，應該優先考慮：

- 玩家能不能直覺理解這個功能。
- 這個功能是否能在遊戲中明確確認有沒有生效。
- 這個功能是否會影響原版遊戲的平衡。
- 這個功能是否容易在之後修改或移除。

## 現有功能

### Goat 測試指令

玩家可以在世界中輸入：

```text
/goat
```

預期結果：

```text
Goat mod is loaded.
```

這個指令的用途是確認模組有正常載入，不是正式玩法的一部分。之後如果模組有更完整的功能，可以保留它作為開發檢查工具，或改成顯示模組狀態。

### Goat GUI

玩家可以按下：

```text
Right Shift
```

開啟 Goat GUI。

目前 GUI 的用途是作為模組功能列表與設定面板的雛形。它已經具備：

- 半透明背景遮罩。
- 可拖曳視窗。
- 可調整視窗大小。
- 左側分類列表。
- 右側模組列表。
- 模組開關。
- 模組設定頁。
- 布林值設定。
- 數值滑桿。
- 模式選單。
- 右鍵返回上一層。

目前已有的分類：

- Combat
- Movement
- Render
- World
- Misc
- Settings

目前已有的示範模組包含：

- Sprint
- ClickGui
- Notification
- Crosshair
- CustomFOV
- FullBright
- ClientSettings
- KillAura
- Velocity
- KeepSprint
- NoSlow
- Speed
- AntiBot
- FarmingMacro

這些模組目前主要用來展示 GUI 的互動與版面，不代表所有遊戲內功能都已實作完成。

### Auto Sprint

玩家可以在 Goat GUI 的 Movement 分類中開啟或關閉：

```text
Sprint
```

當 Sprint 開啟時，玩家在世界中移動時會自動進入奔跑狀態，不需要再按原版 sprint 鍵。

Sprint 目前有兩種模式：

- Legit：只在按住前進鍵時自動奔跑。
- Omni：按住任一方向移動鍵時自動奔跑。

限制條件：

- 只在玩家位於世界中時生效。
- 開啟聊天、背包或 Goat GUI 等畫面時不會主動觸發。
- 玩家正在潛行時不會觸發。
- 玩家正在使用物品時不會觸發。
- 飢餓值不足以奔跑時不會觸發，除非玩家目前具有飛行能力。

測試方式：

1. 進入世界。
2. 按 Right Shift 開啟 Goat GUI。
3. 切換到 Movement 分類。
4. 確認 Sprint 開關為開啟。
5. 關閉 GUI 後按住 W。
6. 玩家應自動進入奔跑狀態。
7. 將 Sprint 模式改為 Omni 後，按住 A、S 或 D 也應自動進入奔跑狀態。
8. 回到 GUI 關閉 Sprint 後，再次移動時不應自動奔跑。

### Module 系統

Goat 的可開關功能應該使用 client module 系統實作。

目前 module 程式位置：

```text
src/client/java/com/justingoat/goat/client/module
```

新增功能時，應優先：

1. 在對應分類資料夾新增一個 module class，例如 `module/movement/AutoSprint.java`。
2. 繼承 `GoatModule`。
3. 在建構子中設定名稱、分類、預設開關狀態與設定項目。
4. 在需要每 tick 執行時覆寫 `tick(MinecraftClient client)`。
5. 到 `ModuleManager` 註冊 module。

GUI 不應該為單一功能手寫同步邏輯。GUI 應直接讀取與修改 module 本身的開關與設定值，讓功能邏輯和畫面使用同一份狀態。

### Config 系統

Goat client 會把 module 的開關狀態與設定值保存到本機設定檔。

設定檔位置：

```text
config/goat/client.json
```

目前會保存：

- module 是否開啟。
- 布林值設定。
- 數值設定。
- 模式設定。

當 Minecraft 啟動並載入 Goat client 時，會自動讀取設定檔。如果設定檔不存在，會用目前預設值建立一份新的設定檔。

當玩家在 Goat GUI 中修改 module 開關或設定值時，會自動保存設定。關閉 client 時也會再保存一次。

如果設定檔中有未知 module、未知設定值，或 mode 值不在允許選項內，Goat 會忽略該項目並保留目前預設值。

測試方式：

1. 啟動遊戲。
2. 按 Right Shift 開啟 Goat GUI。
3. 修改任一 module 開關或設定值，例如關閉 Sprint、調整 CustomFOV 的 FOV、切換 Sprint 模式。
4. 關閉遊戲。
5. 再次啟動遊戲並開啟 Goat GUI。
6. 剛才修改的設定應維持不變。
7. 檢查 `config/goat/client.json`，應能看到對應 module 與設定值。

### FullBright

玩家可以在 Goat GUI 的 Render 分類中開啟或關閉：

```text
FullBright
```

當 FullBright 開啟時，畫面亮度會被提高，讓黑暗環境更容易看清楚。

目前設定：

- Brightness：控制提高後的亮度數值，預設為 10.0。

限制條件：

- 只影響玩家自己的畫面。
- 關閉 FullBright 後，會還原玩家原本的亮度設定。

測試方式：

1. 進入世界。
2. 到洞穴、夜晚或其他較暗的地方。
3. 按 Right Shift 開啟 Goat GUI。
4. 切換到 Render 分類。
5. 開啟 FullBright。
6. 畫面應明顯變亮。
7. 關閉 FullBright 後，亮度應回到開啟前的狀態。

### CustomFOV

玩家可以在 Goat GUI 的 Render 分類中開啟或關閉：

```text
CustomFOV
```

當 CustomFOV 開啟時，玩家的視野角度會被設定為指定數值。

目前設定：

- FOV：控制視野角度，預設為 90，範圍為 30 到 120。

限制條件：

- 只影響玩家自己的畫面。
- 關閉 CustomFOV 後，會還原玩家原本的 FOV 設定。

測試方式：

1. 進入世界。
2. 按 Right Shift 開啟 Goat GUI。
3. 切換到 Render 分類。
4. 開啟 CustomFOV。
5. 調整 FOV 設定。
6. 關閉 GUI 後，畫面視野應套用指定 FOV。
7. 關閉 CustomFOV 後，FOV 應回到開啟前的狀態。

### Farming Macro

玩家可以在 Goat GUI 的 Movement 分類中開啟或關閉：

```text
FarmingMacro
```

當 FarmingMacro 開啟時，會自動在農田內進行蛇行路徑收成（按住 D 撞牆換 W，撞牆換 A，撞牆換 W...以此類推），並自動按住攻擊鍵。
這個功能基於 `KeyBinding` 模擬實作，確保產生的物理與封包狀態與真實玩家完全一致，藉此規避 Hypixel Watchdog 等 Anti-Cheat 偵測。

目前設定：

- Hold Attack：是否在移動時持續按住左鍵。預設為開啟。
- Delay Min (ms)：撞牆換向時的最小隨機延遲。
- Delay Max (ms)：撞牆換向時的最大隨機延遲。

限制條件：

- 開啟聊天欄、背包或 Goat GUI 等畫面時會暫停巨集。
- 關閉 FarmingMacro 後，會自動釋放所有受控的按鍵。

測試方式：

1. 進入世界，建立一條農場軌道，並在轉角處放置方塊作為撞牆依據。
2. 開啟 Goat GUI，切換到 Movement 分類。
3. 開啟 FarmingMacro。
4. 觀察角色是否自動向右 (D) 移動並按住左鍵。
5. 角色撞牆後，應自動短暫向前方 (W) 移動，撞牆後再向左 (A) 移動。
6. 關閉 FarmingMacro，角色應立刻停止所有按鍵動作。

### Failsafe Manager (反查緝系統)

Goat 內建了模組化的 Failsafe 系統，專門用來在自動化過程中保護玩家帳號不被伺服器查緝（特別是 Hypixel Watchdog）。

目前已實作的防禦模組：

1. **Rotation Check**：如果在單一 tick 內，玩家的視角發生超過 45 度的非自然劇烈轉動（代表被管理員強行改變視角測試），系統會觸發警報。
2. **World Change**：如果玩家身處的維度或伺服器被強制改變（代表被管理員拉進審查房間或踢出），系統會觸發警報。

限制條件與行為：

- Failsafe 系統會在背景持續監控。
- 當任何 Failsafe 觸發時，會產生「Emergency（緊急事件）」。
- 如果 FarmingMacro 正在運行且偵測到 Emergency，會**立刻強制關閉**並釋放所有移動與攻擊按鍵，避免造成後續的不正常行為證據。
- Emergency 佇列會依照威脅等級（Priority）處理事件。

測試方式：

1. 開啟 FarmingMacro。
2. 透過其他方式（例如伺服器指令、傳送門）強制改變自己的維度或世界。
3. FarmingMacro 應該會立刻停止所有動作，控制權交還玩家。

### A* Pathfinder Engine (尋路引擎)

Goat 內建了自製的 A* 尋路引擎，針對 1.21.11 的 `VoxelShape` 碰撞箱進行了適配，能繞過障礙物與深坑。

主要功能與元件：
- `AStarPathfinder`: 處理 3D 空間的節點運算。支援水平移動、爬階梯 (Y+1)、安全掉落 (Y-1, Y-2)，並具備「終點懸空自動對齊實體地板」的容錯機制。
- `PathProcessor`: 消化路徑座標，自動接管玩家的前進與跳躍按鍵。
- **平滑轉向 (Smooth Rotation)**: 透過在 `END_CLIENT_TICK` 中更新 `yaw` 與 `pitch`，完全保留 Minecraft 渲染引擎的補間動畫 (Interpolation)，達成極度平滑的模擬轉向效果。
- **卡死重算 (Stuck Detection)**: 持續監控實體位置，若超過 2 秒鐘未產生實質位移 (卡點)，將自動觸發重新尋路，動態繞開臨時障礙物 (例如其他實體或新放的方塊)。
- **路徑視覺化 (Particle ESP)**: 為了規避反作弊的 Render Hook 掃描與克服版號斷層，使用原版原生的粒子系統 (`client.world.addParticle`)，以 `HAPPY_VILLAGER` (綠色星星) 標示節點方塊、以 `END_ROD` (發光點) 畫出路線軌跡。

目前提供一個測試用模組 `PathfinderTest` 與指令：
- 指令 `/goto X Y Z` 可以讓玩家直接測試任何地點的尋路。
- 尋路過程中會自動控制移動。走到目的地後會自動關閉並釋放按鍵。

### 自訂 GUI 字體

Goat GUI 目前支援載入自訂 TrueType 字體。

字體檔案位置固定為：

```text
src/main/resources/assets/goat/font/gui.ttf
```

只要把想使用的 `.ttf` 字體放到這個位置，GUI 文字就會使用該字體。

如果字體不存在或格式無法載入，GUI 會改用系統 SansSerif 字體，避免畫面無法顯示。

目前字體大小設定為較小尺寸，避免自訂字體放進 GUI 後文字過大。文字渲染已關閉抗鋸齒與 fractional metrics，讓小尺寸 GUI 文字更清楚、不容易模糊。

## 載入時行為

當 Minecraft 啟動並載入 Goat 時，模組會在背景留下啟動紀錄。

預期紀錄包含：

```text
Goat mod initialized.
Goat client initialized.
```

這些紀錄用來協助確認模組在遊戲主要部分與玩家畫面部分都有正常啟動。

## 命名規則

所有屬於 Goat 的內容都應該使用 `goat` 作為主要識別名稱。

範例：

```text
goat
goat:silver_horn
goat:charged_goat_fur
```

命名原則：

- 使用小寫英文字母。
- 單字之間使用底線。
- 名稱要能看出物品或功能的用途。
- 不使用空白。
- 不使用中文作為內部識別名稱。

玩家看得到的名稱可以使用英文或中文，依照語言檔設定。

## 新增內容時的共同規則

每次新增玩法內容，都應該同時寫清楚這些項目：

- 名稱：玩家看到的名稱。
- 識別名稱：模組內部使用的固定名稱。
- 出現位置：玩家從哪裡取得或遇到。
- 使用方式：玩家如何使用。
- 預期效果：使用後會發生什麼事。
- 限制條件：什麼情況下不能使用或不會生效。
- 測試方式：如何在遊戲中確認它正常運作。

## 物品設計規則

如果新增物品，必須清楚描述：

- 物品名稱。
- 是否能堆疊。
- 最大堆疊數量。
- 是否能被合成。
- 是否能在創造模式物品欄中找到。
- 使用後是否會消耗。
- 對玩家、方塊、生物或世界的影響。

物品不應該只有名稱，必須有明確用途。

## 方塊設計規則

如果新增方塊，必須清楚描述：

- 方塊名稱。
- 放置後的外觀。
- 玩家是否能破壞。
- 需要什麼工具破壞。
- 破壞後掉落什麼。
- 是否會發光。
- 是否會和玩家或生物互動。
- 是否會隨時間改變。

方塊應該能用簡單方式在遊戲中測試。

## 指令設計規則

如果新增指令，必須清楚描述：

- 指令文字。
- 誰可以使用。
- 是否需要管理員權限。
- 輸入後會看到什麼回應。
- 成功時會發生什麼。
- 失敗時會顯示什麼原因。

指令回應要簡短、明確，不要讓玩家猜測結果。

## 玩家提示規則

當模組需要告訴玩家結果時，文字應該：

- 短。
- 明確。
- 不使用開發者才懂的詞。
- 優先說明玩家需要知道的結果。

例如：

```text
Goat mod is loaded.
```

比下面這種文字更適合玩家：

```text
Initialization callback completed.
```

## 測試規則

每次修改後，至少要做一次下列檢查：

```text
./gradlew build
```

如果是 Windows PowerShell：

```text
.\gradlew.bat build
```

如果修改了遊戲內行為，還要執行：

```text
.\gradlew.bat runClient
```

進入世界後，確認對應功能有正常出現或生效。

目前最基本的檢查是：

```text
/goat
```

聊天欄應顯示：

```text
Goat mod is loaded.
```

如果修改 GUI，還要檢查：

```text
.\gradlew.bat compileClientJava
.\gradlew.bat runClient
```

進入世界後按下：

```text
Right Shift
```

應確認：

- Goat GUI 可以開啟。
- 視窗可以拖曳。
- 右下角可以調整大小。
- 左側分類可以切換。
- 模組開關可以點擊。
- 右鍵可以進入或返回設定頁。
- 自訂字體有正確載入。
- 文字大小不會超出版面。
- 文字邊緣不應明顯模糊。

## 版本管理規則

當只是開發中小修改時，可以維持：

```text
1.0.0
```

當加入第一個正式玩法功能後，可以改成：

```text
1.1.0
```

當修正問題但沒有新增玩法時，可以改成：

```text
1.0.1
```

版本號應該只在準備分享或發布時調整。

## Agent 工作規則

協助開發的 agent 在修改 Goat 時，應該遵守以下規則：

- 先閱讀這份文件。
- 先確認目前模組是否能建置。
- 修改前先理解現有行為。
- 一次只加入清楚範圍內的功能。
- 不移除現有可用功能，除非使用者明確要求。
- 修改完成後，要說明改了什麼、如何測試、產出檔案在哪裡。
- 如果功能會改變玩家體驗，必須補上遊戲內確認方式。

## 下一步候選功能

以下是適合接著開發的小功能：

- 讓 Goat GUI 的設定可以保存到檔案。
- 讓 GUI 字體大小成為可調整設定。
- 讓 GUI 字體路徑可以設定，而不是固定為 `gui.ttf`。
- 新增一個 Goat 主題物品。
- 新增一個 Goat 主題方塊。
- 讓 `/goat` 顯示模組版本與目前狀態。
- 新增創造模式物品欄中的 Goat 分類。
- 新增一個簡單合成配方。

建議優先順序：

1. 讓 Goat GUI 的設定可以保存到檔案。
2. 讓 GUI 字體大小成為可調整設定。
3. 讓 `/goat` 顯示更完整的狀態。
4. 新增第一個物品。
5. 新增第一個方塊。
6. 新增合成配方。

## 文件維護規則

每次加入正式功能後，都應該更新這份文件。

更新時至少補上：

- 新功能名稱。
- 玩家如何使用。
- 預期效果。
- 測試方式。

如果實際遊戲行為和這份文件不同，應該以修正其中一邊為優先，不要讓文件和遊戲長期不一致。
