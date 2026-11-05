# 网盘直链下载操作规范

本文件规定在 Acid 浏览器中免客户端下载百度网盘、UC 网盘、夸克网盘、123 云盘、115 网盘、迅雷网盘、蓝奏云文件的操作流程。

---

## 1. 通用流程

网盘网页端的下载按钮通常会尝试唤起官方客户端。下载网盘文件时按以下流程操作：

1. **检查登录态**：用同源 `fetch` 调用一次网盘接口，确认返回正常。
2. **未登录时请求接管**：

   ```
   browser(action="request_takeover", reason="请先在当前网页登录您的网盘账号，登录完成后点击继续，我将为您免客户端解析直链下载")
   ```

3. **特别注意：百度网盘前置风险确认**：若目标为百度网盘文件，**严禁直接开始下载**，必须严格执行第 2 节的风险告知与用户二次确认 SOP。
4. **分享链接先转存**：多数网盘他人分享的链接（`/s/xxxx`）不直接开放 CDN 直链，需先转存到用户自己的网盘。
5. **获取直链**：按各网盘的接口流程解析出 CDN 直链。
6. **调起下载**：

   ```json
   {"action": "download", "url": "<直链>", "file_name": "<文件名>"}
   ```

   内置下载器自动携带对应网盘的客户端 `User-Agent`、`Referer` 和站点 `Cookie`。

7. **确认弹窗已打开**：返回结果含 `[DOWNLOAD SHEET OPENED] File: ...` 表示下载确认面板已弹出。此时停止调用工具，回复用户：

   > 已为您解析并调出《文件名》的下载确认面板，您可以点击「立即下载」开始下载，或点击「先收起」暂存到下载管理中稍后按需下载。

不要重复调用 `browser(action="download")`。

---

## 2. 百度网盘

域名：`pan.baidu.com` / `yun.baidu.com`

### ⚠️【强制执行规则：前置风险告知与用户确认】

百度网盘对第三方免客户端高速下载与直链解析存在**极其严厉的反作弊与黑名单风控机制（Black UID 机制）**。一旦触发风控，不仅会导致下载速度被严重限制（降至 10~50 KB/s）、转存/分享功能被封禁，**极有可能导致账号被官方直接封号**。

**因此，在处理百度网盘文件的下载请求时，必须无条件遵循以下 SOP：**

1. **详细阐明风险**：在调用任何下载工具前，必须明确向用户说明上述限速与封号风险。
2. **当轮主动停止**：介绍完风险后，**必须立即结束当前会话轮次，严禁在同一轮次中直接调用转存或下载工具**。
3. **等待明确同意**：回复模版参考如下：

   > ⚠️ **风险提示**：百度网盘官方目前风控极严，第三方免客户端直链解析与高速下载极易触发官方风控机制（Black UID），可能导致您的百度账号**被严重限速（降至数十KB/s）甚至导致账号被永久封号**。
   > 
   > 请问您是否已知晓并愿意承担该风险？如果您确认要继续下载，请回复「确认下载」，我将在收到您的明确同意后再为您执行转存与直链下载。

4. **确认后方可继续**：只有在用户收到该提示后、在后续的会话中给出明确的“确认下载/已知晓风险同意继续”等肯定答复后，才能在下一轮会话中继续执行下述转存与取链步骤。若用户犹豫、未明确回复或拒绝，严禁下载。

---

### 第一步：分享页转存

1. 带提取码的分享页，先填提取码：

   ```
   browser(action="fill", ref="...", text="<提取码>", submit=true)
   ```

2. 点击页面上的「保存到网盘」按钮。不要用 `fetch` 直接调 `/share/list` 或 `/share/transfer`，这些接口前端会校验复杂签名与会话 token。
3. 百度移动端 H5 转存后文件默认落在 `/我的资源`，有时在根目录 `/`。

### 第二步：查询文件路径

```
browser(action="eval")
```

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

路径编码用 `encodeURI(f.path)`，保留 `/` 不转义。使用 `encodeURIComponent` 会把 `/` 编成 `%2F`，导致接口返回 `31326 user is not authorized`。

### 第三步：提取 BDUSS 并请求直链

`BDUSS` 属于 `HttpOnly`，`document.cookie` 无法读取，用 `bash` 工具从应用 Cookies 文件中提取：

```sh
BDUSS=$(strings ../../app_webview/Default/Cookies* 2>/dev/null | grep -o 'baidu\.comBDUSS[^_/][^/]*' | tail -n 1 | sed 's/baidu\.comBDUSS//')
STOKEN=$(strings ../../app_webview/Default/Cookies* 2>/dev/null | grep -o 'pan\.baidu\.comSTOKEN[^/]*' | tail -n 1 | sed 's/pan\.baidu\.comSTOKEN//')
TS=$(date +%s)
ENC_PATH="<替换为第二步的 encPath>"

curl -s "https://d.pcs.baidu.com/rest/2.0/pcs/file?method=locatedownload&app_id=250528&clienttype=17&ver=4.0&ant=1&check_blue=1&es=1&esl=1&apn_id=1_-1&freeisp=0&queryfree=0&use=1&dtype=1&eck=1&ehps=1&err_ver=1.0&network_type=WIFI&channel=0&path=${ENC_PATH}&time=${TS}&rand=5ed606e9da222cde0474cdf70eda884b&devuid=0F1E9FC2E084472DA5A61C4CF4C759AF&cuid=0F1E9FC2E084472DA5A61C4CF4C759AF&deviceid=348642637967375013&psign=860a071f77c860e8cea06e4e54c518f3&version=2.2.111.34&version_app=12.24.6&vip=0" \
  -H "Cookie: BDUSS=$BDUSS; STOKEN=$STOKEN" \
  -A "netdisk;12.24.6;piano;android-android;16;JSbridge4.4.0;jointBridge;1.1.0" \
  | grep -o '"url":"https://[^"]*baidupcs\.com/file/[^"]*"' | head -n 1 | sed 's#"url":"##; s#"$##; s#\\/#/#g'
```

返回的 `urls` 候选数组中，`d2-ant.baidu.com` 开头的条目是 AES-CTR 加密流（`"encrypt": 1`），下载后无法直接打开。`baidupcs.com` 开头的才是明文直链。上述命令已自动过滤并提取 `baidupcs.com` 的地址。

### 第四步：调起下载

```json
{
  "action": "download",
  "url": "https://appall01.baidupcs.com/file/...",
  "file_name": "<文件名>"
}
```

---

## 3. UC 网盘

域名：`drive.uc.cn` / `fast.uc.cn`

所有接口可在 `drive.uc.cn` 页面内用同源 `fetch` 调用。

```
browser(action="eval")
```

```javascript
(async () => {
  // 1. 提取分享码：https://drive.uc.cn/s/c13224864eae4 -> c13224864eae4
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

  // 3. 列出分享目录文件（pdir_fid=0 为根目录）
  async function listShareDir(pdir_fid) {
    const url = `https://pc-api.uc.cn/1/clouddrive/share/sharepage/detail?pr=UCBrowser&fr=pc&pwd_id=${pwd_id}&stoken=${encodeURIComponent(stoken)}&pdir_fid=${pdir_fid}&force=0&_page=1&_size=50&_sort=file_type:asc,file_name:asc`;
    const res = await fetch(url, {credentials: "include"}).then(r => r.json());
    return (res.data && res.data.list) || [];
  }

  // 4. 转存到个人网盘根目录并取直链
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
      return {error: "转存失败（可能未登录）", saveRes};
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

拿到 `dlUrl` 与 `fileName` 后调用 `browser(action="download", url=dlUrl, file_name=fileName)`。

---

## 4. 夸克网盘

域名：`pan.quark.cn`

夸克与 UC 同属阿里 `clouddrive` 架构，接口一致，业务基础域名为 `https://drive-pc.quark.cn`，参数通常为 `pr=ucpro&fr=pc`。

### 模式一：免转存直接取链（推荐用于 <=50MB 的小文件）

在分享页面内通过 `browser(action="eval")` 调用：

1. **获取分享 token 与文件信息**：
   - `POST https://drive-pc.quark.cn/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc`，Body `{"pwd_id": "<pwd_id>", "passcode": ""}` 取得 `stoken`；
   - `GET https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=${pwd_id}&stoken=${stoken}&pdir_fid=0` 取得目标文件的 `fid` 与 `share_fid_token`。
2. **直接请求下载接口**：
   ```javascript
   const res = await fetch("https://drive-pc.quark.cn/1/clouddrive/file/download?pr=ucpro&fr=pc", {
     method: "POST",
     headers: {"Content-Type": "application/json"},
     body: JSON.stringify({
       fids: [item.fid],
       fids_token: [item.share_fid_token],
       pwd_id: pwd_id,
       stoken: stoken
     }),
     credentials: "include"
   }).then(r => r.json());
   const dlUrl = res.data && res.data[0] && res.data[0].download_url;
   ```
3. **关键凭据**：此接口响应头中会下发 `__pugs`（游客下载态 Cookie）。直链下载时若缺少 `__pugs` 会报 CDN 412。浏览器同源请求会自动包含；若通过外部工具下载，必须带上该 Cookie。
4. **回退策略**：若接口返回错误码 `31001`（需登录）或 `23018`（文件超出免转存大小限制），自动降级为模式二。

### 模式二：转存到个人网盘后取链（通用/大文件）

1. **转存到根目录**：`POST https://drive-pc.quark.cn/1/clouddrive/share/sharepage/save?pr=ucpro&fr=pc`，轮询 `task` 接口获取 `savedFid`；
2. **获取直链**：在 `browser(action="eval")` 中请求 `POST https://drive-pc.quark.cn/1/clouddrive/file/download?pr=ucpro&fr=pc&sys=win32&ve=3.23.2`，Body `{"fids": [savedFid]}`。
   若接口校验客户端 UA，改用 `bash` 携带以下 UA 调用：
   ```
   User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.12-a038f7b798 Safari/537.36 Channel/pckk_other_ch
   ```
   取 `data[0].download_url`；
3. **调起下载**：`browser(action="download", url=download_url, file_name=file_name)`。

---

## 5. 123 云盘

域名：`123pan.com` / `123865.com` / `123pan.cn`

1. 在分享页用 `browser(action="eval")` 请求：

   ```
   GET /b/api/share/get?limit=100&next=0&orderBy=file_name&orderDirection=asc&shareKey=${shareKey}&ParentFileId=0
   ```

   获取 `FileId`、`Etag`、`Size`、`S3KeyFlag`。

2. 请求 `POST /b/api/share/download/info`，Body：

   ```json
   {"ShareKey": shareKey, "FileID": FileId, "S3keyFlag": S3KeyFlag, "Size": Size, "Etag": Etag}
   ```

   取 `data.DownloadURL`。

3. **真实直链解码与重定向解析（关键对齐）**：
   - 接口返回的 `data.DownloadURL` 通常为 `download-v2` 包装链接（含有 `params=` 查询参数）。
   - 若 URL 中包含 `params=`，需要对 `params=` 后的字符串进行 **Base64 解码**，解码后即可获取真实的 CDN 文件 URL；
   - 若链接返回 `302` 重定向或包含 `redirect_url`，需提取最终重定向地址；
   - **请求头要求**：调起下载时，请求头必须携带 `Referer: https://yun.123pan.cn/`，否则会被 CDN 防盗链判定为非法请求。

4. 调用 `browser(action="download", url=realUrl, file_name=FileName)`。

---

## 6. 115 网盘

域名：`webapi.115.com`（个人盘）/ `proapi.115.com`（电脑端接口）/ `115.com`

**必备请求头**：桌面 Chrome UA + `X-Requested-With: XMLHttpRequest` + `Referer: https://115.com/`。带 Cookie 时 `X-Requested-With` 绝不能省略。

### 第一步：获取分享信息与文件列表

在分享页面调用：
```
GET https://webapi.115.com/share/snap?share_code=${shareCode}&receive_code=${pwd}&cid=0&offset=0&limit=50&format=json
```
- 提取分享者 UID：`data.userinfo.user_id`（注意：115 分享下载必须使用分享者的 `user_id`，而不是当前登录用户的 UID）；
- 从 `data.list` 提取文件列表。注意 115 目录判定特性：文件夹项没有 `fid`，自身 ID 在 `cid` 里；文件项 `fid` 为文件 ID。

### 第二步：获取直链

- **小文件（<500MB）**：可尝试直接请求分享下载接口：
  ```
  GET https://webapi.115.com/share/downurl?dl=1&user_id=${shareUserId}&share_code=${shareCode}&file_id=${fileId}&receive_code=${pwd}
  ```
  取 `data.file_url_302`（优先）或 `data.file_url`。
- **大文件（>=500MB 或报 50028/50029）**：115 网页端接口限制大文件取链（会报“文件大小超出限制”或“当前版本过低”），必须先转存到用户个人网盘：
  1. 调用转存接口：`POST https://webapi.115.com/share/receive`，表单参数：`share_code=${shareCode}&receive_code=${pwd}&file_id=${fileId}&cid=0`；
  2. 转存成功后，进入用户个人盘列表（`GET https://webapi.115.com/files?aid=1&cid=0`）提取该文件的 `pickcode`（即字段 `pc`）；
  3. 请求个人盘下载接口：`GET https://webapi.115.com/files/download?pickcode=${pickcode}` 获取 `data.file_url`。

### 第三步：双 Cookie 规则（强制要求）

115 网页端直链接口响应中会通过 `Set-Cookie` 下发一个 **900 秒有效、路径绑定该对象的独立 CDN Cookie**。
调起下载时，**必须同时携带用户登录态 Cookie 和该响应下发的 CDN Cookie**，且必须带上 `Referer: https://115.com/`。缺少该 CDN Cookie 会直接导致 CDN 403 `no cookie value`。

---

## 7. 迅雷网盘

域名：`pan.xunlei.com` / `api-pan.xunlei.com`

**必备请求头**：`User-Agent` 与 `Referer: https://pan.xunlei.com/`。

### 第一步：分享链接转存到个人盘

迅雷分享直链不允许匿名直接拉取，需在已登录状态下转存到用户网盘中：
1. 在分享页面点击「保存到网盘」，或调用接口：
   ```
   POST https://api-pan.xunlei.com/drive/v1/share/restore
   ```
   提交参数：`share_id`、`pass_code`、`file_ids`、目标目录 `parent_id`。

### 第二步：获取下载直链

通过个人网盘文件详情接口取链：
```
GET https://api-pan.xunlei.com/drive/v1/files/${fileId}?usage=PLAY&thumbnail_size=SIZE_LARGE
```
从响应中提取直链：
- 优先提取 `links['application/octet-stream'].url`；
- 若不存在则提取 `web_content_link`。

### 第三步：调起下载与并发限制注意事项

1. 调用 `browser(action="download", url=downloadUrl, file_name=fileName)`；
2. **迅雷并发连接特别限制**：迅雷 CDN 对单文件并发 Range 连接有严格上限保护（通常为 8 个）。如果下载器并发连接数超过 8，迅雷 CDN 会降级并强制返回 **HTTP 200 整文件**（忽略 Range 请求头），导致多线程分片与断点续传彻底崩溃。因此下载迅雷文件时，并发连接数必须控制在 **8 个以内**。
3. **临时文件清理（可选）**：迅雷直链自带有效签名，成功获取直链后可以在个人网盘中删除转存的临时文件（`POST https://api-pan.xunlei.com/drive/v1/files/batch`），不影响直链的继续下载。

---

## 8. 蓝奏云

### 优享版 `ilanzou.com`

1. 从 URL 提取 `shareId`
2. 请求 `https://apix.ilanzou.com/unproved/share/list?shareId=${shareId}&page=1&limit=50`，取 `fileId`
3. 直链为 `https://apis.ilanzou.com/unproved/file/redirect?fileId=${fileId}`
4. 调用 `browser(action="download", url=...)`

### 经典版 `lanzou*.com`

1. 读取页面内 `iframe` 的 `src`
2. 进入 `iframe` 上下文，获取 `ajaxm.php` 请求参数：`action: 'downprocess'`、`signs`、`sign`、`ves: 1`
3. 发起请求后拼接 `dom + '/file/' + url`
4. 调用 `browser(action="download", url=...)`

---

## 9. 大文件处理与续传规范

1. **直链有效期**：超过 100 MB 的文件，解析出的 CDN 直链通常有时效性（15~30 分钟）。解析出直链后应立即调用 `browser(action="download")`，不要在中间插入无关长时操作。
2. **断点续传**：内置下载器支持标准 RFC 7233 Range 断点续传。下载中断后可在「下载管理」中直接点击继续，无需重新走解析流程。
