(function () {
  let currentPath = "/";
  let currentListing = [];
  let contextTarget = null;

  const el = (id) => document.getElementById(id);
  const fileListEl = el("fileList");
  const breadcrumbsEl = el("breadcrumbs");
  const emptyStateEl = el("emptyState");
  const toastEl = el("toast");
  const progressBar = el("progressBar");
  const progressFill = el("progressFill");
  const contextMenu = el("contextMenu");

  function toast(msg) {
    toastEl.textContent = msg;
    toastEl.classList.add("show");
    setTimeout(() => toastEl.classList.remove("show"), 2200);
  }

  function showProgress(pct) {
    progressBar.classList.add("active");
    progressFill.style.width = pct + "%";
    if (pct >= 100) {
      setTimeout(() => progressBar.classList.remove("active"), 400);
    }
  }

  function fmtSize(bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + " MB";
    return (bytes / (1024 * 1024 * 1024)).toFixed(1) + " GB";
  }

  function fmtDate(ms) {
    if (!ms) return "";
    const d = new Date(ms);
    return d.toLocaleDateString() + " " + d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  }

  function iconFor(item) {
    if (item.isDir) return "📁";
    const ext = item.name.split(".").pop().toLowerCase();
    if (["jpg", "jpeg", "png", "gif", "webp", "svg", "bmp"].includes(ext)) return "🖼️";
    if (["mp4", "mkv", "avi", "mov", "webm"].includes(ext)) return "🎬";
    if (["mp3", "wav", "flac", "ogg", "m4a"].includes(ext)) return "🎵";
    if (["zip", "rar", "7z", "tar", "gz"].includes(ext)) return "🗜️";
    if (["apk"].includes(ext)) return "📦";
    if (["txt", "md", "json", "xml", "log", "csv", "js", "css", "html", "py", "java", "kt", "c", "cpp", "sh"].includes(ext)) return "📄";
    if (ext === "pdf") return "📕";
    return "📃";
  }

  function joinPath(base, name) {
    if (base === "/") return "/" + name;
    return base.replace(/\/$/, "") + "/" + name;
  }

  function authHeaders() {
    return {};
  }

  async function apiGet(path, params) {
    const url = new URL(path, window.location.origin);
    if (params) Object.keys(params).forEach((k) => url.searchParams.set(k, params[k]));
    const res = await fetch(url, { credentials: "include" });
    if (res.status === 401) { toast("Authentication required"); throw new Error("auth"); }
    if (!res.ok) {
      const data = await res.json().catch(() => ({}));
      throw new Error(data.error || ("HTTP " + res.status));
    }
    return res.json();
  }

  async function apiPost(path, body) {
    const res = await fetch(path, {
      method: "POST",
      credentials: "include",
      body: (() => {
        const fd = new FormData();
        fd.append("postData", JSON.stringify(body || {}));
        return fd;
      })()
    });
    if (res.status === 401) { toast("Authentication required"); throw new Error("auth"); }
    if (!res.ok) {
      const data = await res.json().catch(() => ({}));
      throw new Error(data.error || ("HTTP " + res.status));
    }
    return res.json();
  }

  function renderBreadcrumbs() {
    const parts = currentPath.split("/").filter(Boolean);
    let html = '<span data-path="/">🏠 root</span>';
    let acc = "";
    parts.forEach((p) => {
      acc += "/" + p;
      html += ' / <span data-path="' + acc + '">' + p + "</span>";
    });
    breadcrumbsEl.innerHTML = html;
    breadcrumbsEl.querySelectorAll("span").forEach((s) => {
      s.addEventListener("click", () => loadDir(s.getAttribute("data-path")));
    });
  }

  function renderList() {
    fileListEl.innerHTML = "";
    if (currentListing.length === 0) {
      emptyStateEl.style.display = "block";
      return;
    }
    emptyStateEl.style.display = "none";
    currentListing.forEach((item) => {
      const tr = document.createElement("tr");
      tr.className = "fileRow";
      tr.innerHTML =
        '<td class="fileIcon">' + iconFor(item) + "</td>" +
        '<td class="fileName">' + escapeHtml(item.name) + "</td>" +
        '<td class="muted">' + (item.isDir ? "—" : fmtSize(item.size)) + "</td>" +
        '<td class="muted">' + fmtDate(item.lastModified) + "</td>" +
        '<td><button class="rowMenuBtn">⋮</button></td>';

      tr.addEventListener("click", (e) => {
        if (e.target.closest(".rowMenuBtn")) return;
        openItem(item);
      });
      tr.querySelector(".rowMenuBtn").addEventListener("click", (e) => {
        e.stopPropagation();
        openContextMenu(e, item);
      });
      fileListEl.appendChild(tr);
    });
  }

  function escapeHtml(s) {
    const d = document.createElement("div");
    d.textContent = s;
    return d.innerHTML;
  }

  async function loadDir(path) {
    try {
      const data = await apiGet("/api/ls", { path });
      currentPath = path;
      currentListing = data.content || [];
      renderBreadcrumbs();
      renderList();
    } catch (e) {
      toast("Error: " + e.message);
    }
  }

  function isTextLike(name) {
    const ext = name.split(".").pop().toLowerCase();
    return ["txt", "md", "json", "xml", "log", "csv", "js", "css", "html", "py", "java", "kt", "c", "cpp", "sh", "yml", "yaml", "ini", "conf", "gradle"].includes(ext);
  }

  function openItem(item) {
    const path = joinPath(currentPath, item.name);
    if (item.isDir) {
      loadDir(path);
    } else if (isTextLike(item.name)) {
      openTextViewer(path);
    } else {
      window.open("/api/fs?path=" + encodeURIComponent(path), "_blank");
    }
  }

  // ---- context menu -------------------------------------------------------

  function openContextMenu(e, item) {
    contextTarget = item;
    contextMenu.innerHTML = "";
    const path = joinPath(currentPath, item.name);

    const actions = [];
    if (!item.isDir) {
      actions.push(["⭳ Download", () => window.open("/api/fs?path=" + encodeURIComponent(path), "_blank")]);
      if (isTextLike(item.name)) actions.push(["✎ Edit", () => openTextViewer(path)]);
    }
    actions.push(["✏️ Rename", () => promptRename(item)]);
    actions.push(["➡️ Move to path…", () => promptMove(item)]);
    actions.push(["🗑️ Delete", () => confirmDelete(item), "danger"]);

    actions.forEach(([label, fn, cls]) => {
      const b = document.createElement("button");
      b.textContent = label;
      if (cls) b.className = cls;
      b.addEventListener("click", () => { closeContextMenu(); fn(); });
      contextMenu.appendChild(b);
    });

    contextMenu.style.left = Math.min(e.clientX, window.innerWidth - 180) + "px";
    contextMenu.style.top = Math.min(e.clientY, window.innerHeight - 200) + "px";
    contextMenu.classList.add("open");
  }
  function closeContextMenu() { contextMenu.classList.remove("open"); }
  document.addEventListener("click", (e) => { if (!e.target.closest(".contextMenu")) closeContextMenu(); });

  // ---- text viewer/editor ---------------------------------------------------

  let currentTextPath = null;
  function openTextViewer(path) {
    apiGet("/api/read", { path }).then((data) => {
      currentTextPath = path;
      el("textModalTitle").textContent = path;
      el("textEditor").value = data.content;
      el("textModal").classList.add("open");
    }).catch((e) => toast("Cannot open: " + e.message));
  }
  el("closeTextModal").addEventListener("click", () => el("textModal").classList.remove("open"));
  el("saveTextBtn").addEventListener("click", async () => {
    try {
      await apiPost("/api/write", { path: currentTextPath, content: el("textEditor").value });
      toast("Saved");
      el("textModal").classList.remove("open");
    } catch (e) { toast("Save failed: " + e.message); }
  });

  // ---- clipboard ------------------------------------------------------------

  el("btnClipboard").addEventListener("click", () => el("clipModal").classList.add("open"));
  el("closeClipModal").addEventListener("click", () => el("clipModal").classList.remove("open"));
  el("clipGetBtn").addEventListener("click", async () => {
    try {
      const data = await apiGet("/api/clipboard");
      el("clipText").value = data.content || "";
    } catch (e) { toast("Failed: " + e.message); }
  });
  el("clipSetBtn").addEventListener("click", async () => {
    try {
      await apiPost("/api/clipboard", { content: el("clipText").value });
      toast("Sent to device clipboard");
    } catch (e) { toast("Failed: " + e.message); }
  });

  // ---- prompt modal (reused for rename / new folder / move) ------------------

  let promptResolver = null;
  function showPrompt(title, defaultValue) {
    el("promptTitle").textContent = title;
    el("promptInput").value = defaultValue || "";
    el("promptModal").classList.add("open");
    el("promptInput").focus();
    return new Promise((resolve) => { promptResolver = resolve; });
  }
  function closePrompt(result) {
    el("promptModal").classList.remove("open");
    if (promptResolver) { promptResolver(result); promptResolver = null; }
  }
  el("closePromptModal").addEventListener("click", () => closePrompt(null));
  el("promptOkBtn").addEventListener("click", () => closePrompt(el("promptInput").value.trim()));
  el("promptInput").addEventListener("keydown", (e) => { if (e.key === "Enter") closePrompt(el("promptInput").value.trim()); });

  async function promptRename(item) {
    const newName = await showPrompt("Rename '" + item.name + "' to:", item.name);
    if (!newName || newName === item.name) return;
    try {
      await apiPost("/api/rename", { path: joinPath(currentPath, item.name), newName });
      toast("Renamed");
      loadDir(currentPath);
    } catch (e) { toast("Rename failed: " + e.message); }
  }

  async function promptMove(item) {
    const dst = await showPrompt("Move '" + item.name + "' to full path:", joinPath(currentPath, item.name));
    if (!dst) return;
    try {
      await apiPost("/api/move", { src: joinPath(currentPath, item.name), dst });
      toast("Moved");
      loadDir(currentPath);
    } catch (e) { toast("Move failed: " + e.message); }
  }

  function confirmDelete(item) {
    if (!confirm("Delete '" + item.name + "'? This cannot be undone.")) return;
    apiPost("/api/delete", { paths: [joinPath(currentPath, item.name)] })
      .then(() => { toast("Deleted"); loadDir(currentPath); })
      .catch((e) => toast("Delete failed: " + e.message));
  }

  // ---- toolbar actions --------------------------------------------------------

  el("btnRefresh").addEventListener("click", () => loadDir(currentPath));

  el("btnNewFolder").addEventListener("click", async () => {
    const name = await showPrompt("New folder name:", "");
    if (!name) return;
    try {
      await apiPost("/api/mkdir", { path: joinPath(currentPath, name) });
      toast("Folder created");
      loadDir(currentPath);
    } catch (e) { toast("Failed: " + e.message); }
  });

  el("fileInput").addEventListener("change", (e) => uploadFiles(e.target.files));

  async function uploadFiles(fileList) {
    if (!fileList || fileList.length === 0) return;
    const fd = new FormData();
    Array.from(fileList).forEach((f) => fd.append(f.name, f));
    showProgress(5);
    try {
      const xhr = new XMLHttpRequest();
      await new Promise((resolve, reject) => {
        xhr.open("POST", "/api/upload?path=" + encodeURIComponent(currentPath));
        xhr.upload.onprogress = (ev) => {
          if (ev.lengthComputable) showProgress((ev.loaded / ev.total) * 100);
        };
        xhr.onload = () => (xhr.status >= 200 && xhr.status < 300) ? resolve() : reject(new Error("Upload failed"));
        xhr.onerror = () => reject(new Error("Upload failed"));
        xhr.send(fd);
      });
      showProgress(100);
      toast("Upload complete");
      loadDir(currentPath);
    } catch (e) {
      toast("Upload error: " + e.message);
    }
  }

  // drag & drop
  const dropOverlay = el("dropOverlay");
  let dragCounter = 0;
  window.addEventListener("dragenter", (e) => { e.preventDefault(); dragCounter++; dropOverlay.classList.add("active"); });
  window.addEventListener("dragleave", (e) => { e.preventDefault(); dragCounter--; if (dragCounter <= 0) dropOverlay.classList.remove("active"); });
  window.addEventListener("dragover", (e) => e.preventDefault());
  window.addEventListener("drop", (e) => {
    e.preventDefault();
    dragCounter = 0;
    dropOverlay.classList.remove("active");
    if (e.dataTransfer.files.length) uploadFiles(e.dataTransfer.files);
  });

  // ---- device info ------------------------------------------------------------

  async function loadInfo() {
    try {
      const data = await apiGet("/api/info");
      el("deviceInfo").textContent = data.brand + " " + data.model + " · Android SDK " + data.sdk;
    } catch (e) { /* ignore */ }
  }

  loadInfo();
  loadDir("/");
})();
