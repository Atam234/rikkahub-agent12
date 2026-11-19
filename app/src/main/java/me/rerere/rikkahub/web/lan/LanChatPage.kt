package me.rerere.rikkahub.web.lan

// Self-contained page (no external scripts) so it works on a LAN without internet.
// Kept free of the dollar sign so it can live in a Kotlin raw string.
internal val LAN_CHAT_HTML = """
<!doctype html>
<html lang="tl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Umpat Technology · LAN Chat</title>
<style>
  :root { --bg:#0b0b0d; --panel:#16161a; --line:#2a2a30; --text:#f2f2f5; --muted:#9a9aa3;
          --mine:#4f46e5; --theirs:#1f1f25; --accent:#a855f7; }
  * { box-sizing:border-box; }
  html, body { margin:0; height:100%; background:var(--bg); color:var(--text);
               font:15px/1.4 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
  body { display:flex; flex-direction:column; }
  header { padding:12px 16px; border-bottom:1px solid var(--line); background:var(--panel); }
  header h1 { margin:0; font-size:17px; }
  header h1 span { background:linear-gradient(90deg,#4f8dff,var(--accent));
                   -webkit-background-clip:text; background-clip:text; color:transparent; }
  header p { margin:4px 0 0; color:var(--muted); font-size:12px; word-break:break-all; }
  #log { flex:1; overflow-y:auto; padding:12px 16px; display:flex; flex-direction:column; gap:10px; }
  .msg { max-width:82%; display:flex; flex-direction:column; gap:3px; }
  .msg.mine { align-self:flex-end; align-items:flex-end; }
  .who { font-size:11px; color:var(--muted); }
  .bubble { padding:8px 12px; border-radius:16px; background:var(--theirs);
            white-space:pre-wrap; word-wrap:break-word; }
  .mine .bubble { background:var(--mine); }
  .bubble img { display:block; max-width:100%; max-height:320px; border-radius:10px; margin:2px 0; }
  form { display:flex; gap:8px; padding:10px 12px calc(10px + env(safe-area-inset-bottom));
         border-top:1px solid var(--line); background:var(--panel); align-items:flex-end; }
  textarea { flex:1; resize:none; min-height:40px; max-height:120px; padding:10px 12px;
             border-radius:20px; border:1px solid var(--line); background:var(--bg); color:var(--text);
             font:inherit; }
  button, label.pick { border:0; border-radius:20px; height:40px; padding:0 14px; cursor:pointer;
                       font:inherit; color:#fff; background:linear-gradient(90deg,#4f8dff,var(--accent)); }
  label.pick { display:flex; align-items:center; background:var(--theirs); border:1px solid var(--line); }
  #preview { font-size:12px; color:var(--muted); padding:0 16px 6px; display:none; }
  .empty { color:var(--muted); text-align:center; margin-top:30vh; }
</style>
</head>
<body>
<header>
  <h1><span>Umpat Technology</span> · LAN Chat</h1>
  <p id="addr">Kumokonekta…</p>
</header>
<div id="log"><div class="empty" id="empty">Wala pang mensahe. Kumusta sa lahat!</div></div>
<div id="preview"></div>
<form id="form">
  <label class="pick" title="Magpadala ng larawan">🖼️<input id="file" type="file" accept="image/*" hidden></label>
  <textarea id="text" rows="1" placeholder="Mag-type ng mensahe…"></textarea>
  <button type="submit">Send</button>
</form>
<script>
(function () {
  var log = document.getElementById('log');
  var empty = document.getElementById('empty');
  var form = document.getElementById('form');
  var textBox = document.getElementById('text');
  var fileInput = document.getElementById('file');
  var preview = document.getElementById('preview');
  var seen = {};
  var pendingImage = null;

  var name = '';
  try { name = localStorage.getItem('lan_name') || ''; } catch (e) {}
  if (!name) {
    name = (prompt('Ano ang pangalan mo?') || '').trim() || ('Bisita ' + Math.floor(Math.random() * 900 + 100));
    try { localStorage.setItem('lan_name', name); } catch (e) {}
  }

  function scrollDown() { log.scrollTop = log.scrollHeight; }

  function render(m) {
    if (seen[m.id]) return;
    seen[m.id] = true;
    if (empty) { empty.remove(); empty = null; }
    var row = document.createElement('div');
    row.className = 'msg' + (m.name === name ? ' mine' : '');
    var who = document.createElement('div');
    who.className = 'who';
    who.textContent = m.name + ' · ' + new Date(m.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    var bubble = document.createElement('div');
    bubble.className = 'bubble';
    if (m.imageId) {
      var img = document.createElement('img');
      img.src = '/api/lan/images/' + encodeURIComponent(m.imageId);
      img.onload = scrollDown;
      img.onclick = function () { window.open(img.src, '_blank'); };
      bubble.appendChild(img);
    }
    if (m.text) {
      var t = document.createElement('div');
      t.textContent = m.text;
      bubble.appendChild(t);
    }
    row.appendChild(who);
    row.appendChild(bubble);
    log.appendChild(row);
    scrollDown();
  }

  fetch('/api/lan/info').then(function (r) { return r.json(); }).then(function (info) {
    var links = info.addresses.map(function (a) { return 'http://' + a + ':' + info.port + '/lan'; });
    document.getElementById('addr').textContent = links.length
      ? 'Sa ibang phone sa parehong Wi-Fi, buksan: ' + links.join('  o  ')
      : 'Kumonekta sa Wi-Fi para makasali ang ibang phone.';
  }).catch(function () {});

  fetch('/api/lan/messages').then(function (r) { return r.json(); }).then(function (list) {
    list.forEach(render);
  });

  function connect() {
    var source = new EventSource('/api/lan/stream');
    source.addEventListener('message', function (e) {
      try { render(JSON.parse(e.data)); } catch (err) {}
    });
    source.onerror = function () {
      source.close();
      setTimeout(function () {
        fetch('/api/lan/messages').then(function (r) { return r.json(); }).then(function (list) { list.forEach(render); });
        connect();
      }, 3000);
    };
  }
  connect();

  // Shrink pictures before sending so they travel fast over Wi-Fi.
  fileInput.addEventListener('change', function () {
    var file = fileInput.files && fileInput.files[0];
    if (!file) return;
    var reader = new FileReader();
    reader.onload = function () {
      var img = new Image();
      img.onload = function () {
        var max = 1600;
        var scale = Math.min(1, max / Math.max(img.width, img.height));
        var canvas = document.createElement('canvas');
        canvas.width = Math.round(img.width * scale);
        canvas.height = Math.round(img.height * scale);
        canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);
        pendingImage = canvas.toDataURL('image/jpeg', 0.85);
        preview.style.display = 'block';
        preview.textContent = 'Nakahandang ipadala: ' + file.name + ' (i-tap ang Send)';
      };
      img.src = reader.result;
    };
    reader.readAsDataURL(file);
  });

  form.addEventListener('submit', function (e) {
    e.preventDefault();
    var text = textBox.value.trim();
    if (!text && !pendingImage) return;
    var body = { name: name, text: text, image: pendingImage };
    textBox.value = '';
    pendingImage = null;
    fileInput.value = '';
    preview.style.display = 'none';
    fetch('/api/lan/messages', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }).then(function (r) {
      if (!r.ok) return r.text().then(function (msg) { alert(msg || 'Hindi naipadala'); });
      return r.json().then(render);
    }).catch(function () { alert('Hindi naipadala. Tingnan ang Wi-Fi.'); });
  });

  textBox.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); form.requestSubmit(); }
  });
})();
</script>
</body>
</html>
"""
