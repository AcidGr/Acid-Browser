# Skill: Cloud Drive App-Free Direct Download (网盘免APP直链解析与下载)

> 免安装客户端直接解析并调用浏览器内置多线程下载器下载百度网盘、UC网盘、夸克网盘、123云盘、蓝奏云等分享或个人网盘文件。

---

## 1. 核心原则与闭环感知规则（必读）

各大网盘（百度网盘、UC网盘、夸克网盘等）的网页端（尤其是移动端 H5）在点击“下载”按钮时会强制拦截并尝试唤起流氓 APP（或报错要求安装客户端），无法直接下载。

当用户要求下载网盘中的文件时，**严禁直接点击网页上的“去APP查看 / 下载客户端 / 客户端下载”按钮**，必须遵循以下标准闭环流程：

1. **复用浏览器内置登录态（Cookie）**：
   - 先通过同源 `fetch` 检查当前网盘是否已登录。如果接口返回未登录错误，立即调用 `browser(action="request_takeover", reason="请先在当前网页登录您的网盘账号，登录完成后点击继续，我将为您免APP解析直链下载")` 请用户完成登录。
2. **分享文件先转存到自己的网盘**：
   - 如果是别人分享的链接（如 `/s/xxxx`），网盘不允许直接获取高速直链，必须先将目标文件转存到用户自己的网盘。
3. **统一使用 `browser(action="download", url=..., file_name=...)` 调起浏览器内置下载器**：
   - 拿到真实 CDN 直链 `dlUrl` 后，**优先直接调用**：
     ```json
     {"action": "download", "url": "<直链URL>", "file_name": "<文件名>"}
     ```
   - `browser(action="download")` 会直接打开浏览器原生的「新建下载」确认面板，并由内置 16 线程下载器自动携带对应网盘的客户端专属 `User-Agent`（如百度 `netdisk;12.24.6...`、夸克 `quark-cloud-drive...`）、`Referer` 与主站 `Cookie`，**彻底避免 WebView 因普通浏览器 UA 访问大文件直链而遭遇 `403 Forbidden` 白屏**！
   - **感知下载弹窗调起**：当返回结果包含 **`[DOWNLOAD SHEET OPENED] File: ...`** 时，说明原生的「新建下载」确认面板已经成功在用户屏幕上弹出！
   - **严禁重复触发**：看到 `[DOWNLOAD SHEET OPENED]` 后**立即停止调用任何工具**，直接回复用户：“已为您解析并调出《文件名》的下载确认面板，您可以点击「立即下载」开始内置多线程下载，或点击「先收起」暂存到下载管理中稍后按需下载。”

---

## 2. 百度网盘 (`pan.baidu.com` / `yun.baidu.com`) 标准解析流程（含大文件避坑指南）

百度网盘获取高速直链需要使用客户端专有 `locatedownload` 接口并携带 `HttpOnly` 的 `BDUSS` 与客户端专属 `User-Agent`。请严格按以下 4 步执行：

### 第一步：如果是分享页 (`/s/1xxxx`)，先点击页面「保存到网盘」并查询转存路径
1. **带提取码的分享页**：先用 `browser(action="fill", ref="...", text="<提取码>", submit=true)` 输入提取码进入分享文件页。
2. **点击页面上的「保存到网盘」按钮**：
   - ⚠️ **避坑铁律 1**：在百度移动端 H5 分享页中，**不要**自己用 `fetch` 调 `/share/list` 或 `/share/transfer`（百度 H5 会校验签名并返回 `errno: 2`）。直接用 `browser(action="click")` 点击页面上的 **「保存到网盘」** 按钮（如果弹出目录选择确认框，再点击确认保存）。
   - ⚠️ **避坑铁律 2**：百度移动端 H5 点击「保存到网盘」后，默认会将文件保存在 **`/我的资源`** 目录下（有时在根目录 `/`）。
3. **查询文件的完整 `path` 与 `encodeURI(path)`**：
   - 用 `browser(action="eval")` 同时扫描 `/我的资源` 和 `/` 根目录，找到刚保存的目标文件（`isdir === 0`）：
     ```javascript
     (async () => {
       for (const dir of ["/我的资源", "/"]) {
         const r = await fetch(`https://pan.baidu.com/api/list?clienttype=0&app_id=250528&web=1&order=time&desc=1&dir=${encodeURIComponent(dir)}&num=50&page=1`, {
           credentials: "include"
         }).then(x => x.json());
         const files = (r.list || []).filter(f => f.isdir === 0).map(f => ({
           name: f.server_filename,
           path: f.path,
           encPath: encodeURI(f.path),
           size: f.size
         }));
         if (files.length > 0) return {dir, files: files.slice(0, 10)};
       }
       return {error: "未找到文件"};
     })()
     ```
   - ⚠️ **避坑铁律 3**：`path` 中的斜杠 `/` **绝对不能**编码成 `%2F`！必须使用 `encodeURI(f.path)`（保留原始 `/`），不能用 `encodeURIComponent(f.path)`，否则百度 PCS 接口会报错 `31326 user is not authorized`。

### 第二步：在 `bash` 工具中提取 `BDUSS`/`STOKEN` 并请求 `locatedownload` 获取明文直链
网页 `document.cookie` 无法读取 `HttpOnly` 的 `BDUSS`。请调用 `bash` 工具执行以下命令（将 `ENC_PATH` 替换为第一步拿到的 `encPath`）：
```sh
BDUSS=$(strings ../../app_webview/Default/Cookies* 2>/dev/null | grep -o 'baidu\.comBDUSS[^_/][^/]*' | tail -n 1 | sed 's/baidu\.comBDUSS//')
STOKEN=$(strings ../../app_webview/Default/Cookies* 2>/dev/null | grep -o 'pan\.baidu\.comSTOKEN[^/]*' | tail -n 1 | sed 's/pan\.baidu\.comSTOKEN//')
TS=$(date +%s)
ENC_PATH="<替换为第一步的 encPath，例如 /%E6%88%91%E7%9A%84%E8%B5%84%E6%BA%90/xxx.apk>"

curl -s "https://d.pcs.baidu.com/rest/2.0/pcs/file?method=locatedownload&app_id=250528&clienttype=17&ver=4.0&ant=1&check_blue=1&es=1&esl=1&apn_id=1_-1&freeisp=0&queryfree=0&use=1&dtype=1&eck=1&ehps=1&err_ver=1.0&network_type=WIFI&channel=0&path=${ENC_PATH}&time=${TS}&rand=5ed606e9da222cde0474cdf70eda884b&devuid=0F1E9FC2E084472DA5A61C4CF4C759AF&cuid=0F1E9FC2E084472DA5A61C4CF4C759AF&deviceid=348642637967375013&psign=860a071f77c860e8cea06e4e54c518f3&version=2.2.111.34&version_app=12.24.6&vip=0" \
  -H "Cookie: BDUSS=$BDUSS; STOKEN=$STOKEN" \
  -A "netdisk;12.24.6;piano;android-android;16;JSbridge4.4.0;jointBridge;1.1.0" \
  | grep -o '"url":"https://[^"]*baidupcs\.com/file/[^"]*"' | head -n 1 | sed 's#"url":"##; s#"$##; s#\\/#/#g'
```
- ⚠️ **避坑铁律 4（最关键！严禁直接取 `urls[0]`）**：
  对于大文件（如 >50MB 的 APK/ZIP），百度返回的 `urls[0]` 往往是 `https://d2-ant.baidu.com/...` 且带有 `"encrypt": 1`（这是百度私有 AES-CTR 加密流，直接下载无法解压/安装且会 302 报错）！
  而排在后面的 `https://appall01.baidupcs.com/file/...` 才是 `"encrypt": 0` 的标准未加密明文直链！因此上面的管道命令专门用 `grep -o '"url":"https://[^"]*baidupcs\.com/file/[^"]*"' | head -n 1` 只提取 `baidupcs.com` 的明文直链！

### 第三步：调用 `browser(action="download")` 调起浏览器内置下载器
拿到 `https://*.baidupcs.com/file/...` 直链后，**直接调用**：
```json
{
  "action": "download",
  "url": "https://appall01.baidupcs.com/file/...",
  "file_name": "<文件名，例如 高德地图.apk>"
}
```
看到返回 `[DOWNLOAD SHEET OPENED]` 后立即结束任务并告知用户点击「立即下载」或「先收起」！

---

## 3. UC 网盘 (`drive.uc.cn` / `fast.uc.cn`) 标准解析流程

UC 网盘的所有接口均可在 `drive.uc.cn` 页面内通过 `browser(action="eval")` 使用同源 `fetch(..., {credentials: "include"})` 一次性拿直链，最后调 `browser(action="download")`（或在 JS 内赋给 `window.location.href`）：

```javascript
(async () => {
  // 1. 提取分享码 pwd_id (例如 https://drive.uc.cn/s/c13224864eae4 -> c13224864eae4)
  const m = location.pathname.match(/\/s\/([a-zA-Z0-9]+)/);
  const pwd_id = m ? m[1] : "";
  if (!pwd_id) return {error: "未识别到分享 pwd_id"};

  // 2. 获取分享 stoken
  const tokRes = await fetch("https://pc-api.uc.cn/1/clouddrive/share/sharepage/token?pr=UCBrowser&fr=pc", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({pwd_id, passcode: ""}),
    credentials: "include"
  }).then(r => r.json());
  const stoken = tokRes.data && tokRes.data.stoken;
  if (!stoken) return {error: "获取 stoken 失败", tokRes};

  // 3. 列出分享目录文件 (pdir_fid=0 为根目录)
  async function listShareDir(pdir_fid) {
    const url = `https://pc-api.uc.cn/1/clouddrive/share/sharepage/detail?pr=UCBrowser&fr=pc&pwd_id=${pwd_id}&stoken=${encodeURIComponent(stoken)}&pdir_fid=${pdir_fid}&force=0&_page=1&_size=50&_sort=file_type:asc,file_name:asc`;
    const res = await fetch(url, {credentials: "include"}).then(r => r.json());
    return (res.data && res.data.list) || [];
  }

  // 4. 转存目标文件 item 到个人网盘根目录 (to_pdir_fid: "0") 并获取直链
  async function saveAndGetDirectUrl(item) {
    const saveRes = await fetch("https://pc-api.uc.cn/1/clouddrive/share/sharepage/save?pr=UCBrowser&fr=pc", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({
        fid_list: [item.fid],
        fid_token_list: [item.share_fid_token],
        to_pdir_fid: "0",
        pwd_id,
        stoken,
        pdir_fid: item.pdir_fid || "0",
        scene: "link"
      }),
      credentials: "include"
    }).then(r => r.json());

    if (saveRes.code !== 0 && saveRes.status !== 200) {
      return {error: "转存失败(可能未登录)", saveRes};
    }

    const taskId = saveRes.data && saveRes.data.task_id;
    let savedFid = null;
    for (let i = 0; i < 5; i++) {
      await new Promise(r => setTimeout(r, 500));
      const taskRes = await fetch(`https://pc-api.uc.cn/1/clouddrive/task?pr=UCBrowser&fr=pc&task_id=${taskId}&retry_index=${i}`, {
        credentials: "include"
      }).then(r => r.json());
      const topFids = taskRes.data && taskRes.data.save_as && taskRes.data.save_as.save_as_top_fids;
      if (topFids && topFids.length > 0) {
        savedFid = topFids[0];
        break;
      }
    }
    if (!savedFid) return {error: "未获取到转存后的 fid"};

    // 5. 请求 PC 客户端直链接口
    const dlRes = await fetch("https://pc-api.uc.cn/1/clouddrive/file/download?entry=ft&fr=pc&pr=UCBrowser", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({fids: [savedFid]}),
      credentials: "include"
    }).then(r => r.json());

    const dlUrl = dlRes.data && dlRes.data[0] && dlRes.data[0].download_url;
    return {ok: !!dlUrl, fileName: dlRes.data && dlRes.data[0] && dlRes.data[0].file_name, dlUrl};
  }
})()
```
拿到 `dlUrl` 和 `fileName` 后，调用 `browser(action="download", url=dlUrl, file_name=fileName)` 即可弹出下载面板。

---

## 4. 夸克网盘 (`pan.quark.cn`) 标准解析流程

夸克网盘与 UC 网盘同属阿里系 `clouddrive` 架构，转存与目录查询接口与 UC 网盘一致（域名替换为 `https://drive-pc.quark.cn`，参数为 `pr=ucpro&fr=pc`）：

1. **分享页获取 `stoken` 与转存**（在 `pan.quark.cn` 页面通过 `browser(action="eval")` 执行）：
   - 获取 Token：`POST https://drive-pc.quark.cn/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc`（Body: `{"pwd_id": "<pwd_id>", "passcode": ""}`）
   - 分享文件列表：`GET https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=${pwd_id}&stoken=${encodeURIComponent(stoken)}&pdir_fid=0&_page=1&_size=50`
   - 转存到根目录：`POST https://drive-pc.quark.cn/1/clouddrive/share/sharepage/save?pr=ucpro&fr=pc`
2. **获取直链**：
   - 先尝试在 `browser(action="eval")` 中请求 `POST https://drive-pc.quark.cn/1/clouddrive/file/download?pr=ucpro&fr=pc&sys=win32&ve=3.23.2`（Body: `{"fids": [savedFid]}`）。
   - 如果接口校验客户端 UA，则在 `bash` 中携带 `User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.12-a038f7b798 Safari/537.36 Channel/pckk_other_ch` 调用上述接口拿到 `data[0].download_url`。
3. **调起浏览器内置下载器**：
   - 调用 `browser(action="download", url=download_url, file_name=file_name)`（内置下载器已自动内置夸克客户端 UA 与跨子域 Cookie 注入）。

---

## 5. 123 云盘 (`123pan.com` / `123865.com` / `123pan.cn`) & 蓝奏云 (`lanzou*` / `ilanzou.com`)

### A. 123 云盘
1. 在分享页通过 `browser(action="eval")` 调用 `GET /b/api/share/get?limit=100&next=0&orderBy=file_name&orderDirection=asc&shareKey=${shareKey}&ParentFileId=0` 获取文件对象的 `FileId`、`Etag`、`Size`、`S3KeyFlag`。
2. 调用 `POST /b/api/share/download/info`（Body: `{"ShareKey": shareKey, "FileID": FileId, "S3keyFlag": S3KeyFlag, "Size": Size, "Etag": Etag}`）获取 `data.DownloadURL`。
3. 调用 `browser(action="download", url=DownloadURL, file_name=FileName)` 调起内置下载器。

### B. 蓝奏云优享版 (`ilanzou.com`) / 蓝奏云 (`lanzou*.com`)
- **蓝奏云优享版 (`ilanzou.com`)**：提取 URL 中的 `shareId`，请求 `https://apix.ilanzou.com/unproved/share/list?shareId=${shareId}&page=1&limit=50` 获取 `fileId`，再构造直链 `https://apis.ilanzou.com/unproved/file/redirect?fileId=${fileId}` 并调用 `browser(action="download", url=...)`。
- **经典蓝奏云 (`lanzou*.com`)**：读取页面内 `iframe` 的 `src`，进入 `iframe` 上下文获取 `ajaxm.php` 请求参数（`action: 'downprocess', signs, sign, ves: 1`），请求后拼接 `dom + '/file/' + url` 并调用 `browser(action="download", url=...)`。
