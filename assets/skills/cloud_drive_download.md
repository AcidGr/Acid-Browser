# 网盘直链下载操作规范

本文件规定在 Acid 浏览器中免客户端下载百度网盘、UC 网盘、夸克网盘、123 云盘、蓝奏云文件的操作流程。

---

## 1. 通用流程

网盘网页端的下载按钮会尝试唤起客户端。下载网盘文件时按以下流程操作：

1. **检查登录态**：用同源 `fetch` 调用一次网盘接口，确认返回正常。
2. **未登录时请求接管**：

   ```
   browser(action="request_takeover", reason="请先在当前网页登录您的网盘账号，登录完成后点击继续，我将为您免客户端解析直链下载")
   ```

3. **分享链接先转存**：他人分享的链接（`/s/xxxx`）不提供直链，需先转存到用户自己的网盘。
4. **获取直链**：按各网盘的接口流程解析出 CDN 直链。
5. **调起下载**：

   ```json
   {"action": "download", "url": "<直链>", "file_name": "<文件名>"}
   ```

   内置下载器自动携带对应网盘的客户端 `User-Agent`、`Referer` 和站点 `Cookie`。

6. **确认弹窗已打开**：返回结果含 `[DOWNLOAD SHEET OPENED] File: ...` 表示下载确认面板已弹出。此时停止调用工具，回复用户：

   > 已为您解析并调出《文件名》的下载确认面板，您可以点击「立即下载」开始下载，或点击「先收起」暂存到下载管理中稍后按需下载。

不要重复调用 `browser(action="download")`。

---

## 2. 百度网盘

域名：`pan.baidu.com` / `yun.baidu.com`

获取直链需要客户端专用 `locatedownload` 接口，并携带 `HttpOnly` 的 `BDUSS` 与客户端 `User-Agent`。

### 第一步：分享页转存

1. 带提取码的分享页，先填提取码：

   ```
   browser(action="fill", ref="...", text="<提取码>", submit=true)
   ```

2. 点击页面上的「保存到网盘」按钮，不要用 `fetch` 直接调 `/share/list` 或 `/share/transfer`，这些接口会校验签名。
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

`BDUSS` 是 `HttpOnly`，`document.cookie` 读不到，用 `bash` 工具从 Cookies 文件提取：

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

返回的 `urls` 数组中，`d2-ant.baidu.com` 开头的条目是 AES-CTR 加密流（`"encrypt": 1`），下载后无法解压或安装。`baidupcs.com` 开头的才是明文直链。上述命令只提取 `baidupcs.com` 的地址。

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

夸克与 UC 同属阿里 `clouddrive` 架构，接口一致，域名替换为 `https://drive-pc.quark.cn`，参数为 `pr=ucpro&fr=pc`。

1. **获取 token**：`POST https://drive-pc.quark.cn/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc`，Body `{"pwd_id": "<pwd_id>", "passcode": ""}`
2. **列出分享文件**：`GET https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=${pwd_id}&stoken=${encodeURIComponent(stoken)}&pdir_fid=0&_page=1&_size=50`
3. **转存到根目录**：`POST https://drive-pc.quark.cn/1/clouddrive/share/sharepage/save?pr=ucpro&fr=pc`
4. **获取直链**：先在 `browser(action="eval")` 中请求 `POST https://drive-pc.quark.cn/1/clouddrive/file/download?pr=ucpro&fr=pc&sys=win32&ve=3.23.2`，Body `{"fids": [savedFid]}`。

   若接口校验客户端 UA，改用 `bash` 携带以下 UA 调用：

   ```
   User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.12-a038f7b798 Safari/537.36 Channel/pckk_other_ch
   ```

   取 `data[0].download_url`。

5. **调起下载**：`browser(action="download", url=download_url, file_name=file_name)`

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

3. 调用 `browser(action="download", url=DownloadURL, file_name=FileName)`。

---

## 6. 蓝奏云

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

## 7. 大文件处理

超过 100 MB 的文件，直链有有效期（通常几十分钟）。解析出直链后立即调用 `browser(action="download")`，不要先做其他操作。

内置下载器支持断点续传。下载中断后可在「下载管理」中继续，无需重新解析。
