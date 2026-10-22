// Minimal HTTP server (chrome.sockets.tcpServer) – audio upload + screen frames + PCM.
(function (g) {
  var serverSocketId = null;
  var clients = {};
  var PORT = 8080;
  var onAudio = null;
  var onFrame = null;
  var onPcm = null;
  var onRegister = null;
  var onLog = function () {};

  function str2ab(s) {
    var buf = new ArrayBuffer(s.length);
    var v = new Uint8Array(buf);
    for (var i = 0; i < s.length; i++) v[i] = s.charCodeAt(i) & 0xff;
    return buf;
  }

  function ab2str(buf) {
    var v = new Uint8Array(buf);
    var s = '';
    for (var i = 0; i < v.length; i++) s += String.fromCharCode(v[i]);
    return s;
  }

  function respond(socketId, status, contentType, bodyAb) {
    var body = bodyAb || new ArrayBuffer(0);
    var head =
      'HTTP/1.1 ' + status + '\r\n' +
      'Access-Control-Allow-Origin: *\r\n' +
      'Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n' +
      'Access-Control-Allow-Headers: Content-Type\r\n' +
      'Content-Type: ' + contentType + '\r\n' +
      'Content-Length: ' + body.byteLength + '\r\n' +
      'Connection: close\r\n\r\n';
    var headAb = str2ab(head);
    var out = new Uint8Array(headAb.byteLength + body.byteLength);
    out.set(new Uint8Array(headAb), 0);
    out.set(new Uint8Array(body), headAb.byteLength);
    chrome.sockets.tcp.send(socketId, out.buffer, function () {
      chrome.sockets.tcp.disconnect(socketId, function () {
        chrome.sockets.tcp.close(socketId);
      });
    });
  }

  function parseHeaders(text) {
    var lines = text.split('\r\n');
    var req = lines[0] || '';
    var parts = req.split(' ');
    var method = parts[0] || '';
    var path = parts[1] || '/';
    var headers = {};
    var contentLength = 0;
    for (var i = 1; i < lines.length; i++) {
      if (!lines[i]) break;
      var idx = lines[i].indexOf(':');
      if (idx > 0) {
        var k = lines[i].substring(0, idx).trim().toLowerCase();
        var v = lines[i].substring(idx + 1).trim();
        headers[k] = v;
        if (k === 'content-length') contentLength = parseInt(v, 10) || 0;
      }
    }
    return { method: method, path: path, headers: headers, contentLength: contentLength, contentType: headers['content-type'] || '' };
  }

  function onReceive(info) {
    var c = clients[info.socketId];
    if (!c) return;
    var chunk = new Uint8Array(info.data);
    if (!c.headerDone) {
      var combined = new Uint8Array(c.buf.length + chunk.length);
      combined.set(c.buf, 0);
      combined.set(chunk, c.buf.length);
      c.buf = combined;
      var text = ab2str(c.buf.buffer);
      var sep = text.indexOf('\r\n\r\n');
      if (sep < 0) return;
      var headerText = text.substring(0, sep);
      var parsed = parseHeaders(headerText);
      c.method = parsed.method;
      c.path = parsed.path;
      c.contentLength = parsed.contentLength;
      c.contentType = parsed.contentType;
      c.headerDone = true;
      var headerBytes = sep + 4;
      c.body = new Uint8Array(Math.max(0, c.buf.length - headerBytes));
      if (c.buf.length > headerBytes) c.body.set(c.buf.subarray(headerBytes));
      c.got = c.body.length;
    } else {
      var nb = new Uint8Array(c.got + chunk.length);
      nb.set(c.body.subarray(0, c.got), 0);
      nb.set(chunk, c.got);
      c.body = nb;
      c.got += chunk.length;
    }

    if (c.method === 'OPTIONS') {
      respond(info.socketId, '204 No Content', 'text/plain', str2ab(''));
      delete clients[info.socketId];
      return;
    }

    if (c.method === 'GET') {
      var html =
        '<!doctype html><html><body style="font-family:sans-serif;background:#111;color:#eee;padding:24px">' +
        '<h1>ScreenLink receiver</h1><p>POST /frame (JPEG), /pcm, /play, or /register here.</p></body></html>';
      respond(info.socketId, '200 OK', 'text/html; charset=utf-8', str2ab(html));
      delete clients[info.socketId];
      return;
    }

    if (c.method === 'POST' && c.got >= c.contentLength) {
      var payload = c.body.buffer.slice(0, c.contentLength);
      if (c.path.indexOf('/register') === 0) {
        var s = '';
        try {
          var u8p = new Uint8Array(payload);
          for (var i = 0; i < u8p.length; i++) s += String.fromCharCode(u8p[i]);
        } catch (e) {}
        if (onRegister) onRegister(s);
        respond(info.socketId, '200 OK', 'text/plain', str2ab('OK'));
        delete clients[info.socketId];
        return;
      }
      if (c.path.indexOf('/play') === 0) {
        if (onAudio) onAudio(payload, c.contentType);
        respond(info.socketId, '200 OK', 'text/plain', str2ab('OK'));
        delete clients[info.socketId];
        return;
      }
      if (c.path.indexOf('/frame') === 0) {
        if (onFrame) onFrame(payload);
        respond(info.socketId, '200 OK', 'text/plain', str2ab('OK'));
        delete clients[info.socketId];
        return;
      }
      if (c.path.indexOf('/pcm') === 0) {
        if (onPcm) onPcm(payload);
        respond(info.socketId, '200 OK', 'text/plain', str2ab('OK'));
        delete clients[info.socketId];
        return;
      }
      respond(info.socketId, '404 Not Found', 'text/plain', str2ab('not found'));
      delete clients[info.socketId];
    }
  }

  function start(port, cb) {
    PORT = port || 8080;
    chrome.sockets.tcpServer.create({}, function (createInfo) {
      serverSocketId = createInfo.socketId;
      chrome.sockets.tcpServer.listen(serverSocketId, '0.0.0.0', PORT, function (result) {
        if (result < 0) {
          onLog('Listen failed: ' + result);
          if (cb) cb(false);
          return;
        }
        onLog('Listening on port ' + PORT);
        if (cb) cb(true);
      });
    });
  }

  function stop() {
    if (serverSocketId != null) {
      chrome.sockets.tcpServer.close(serverSocketId);
      serverSocketId = null;
    }
    clients = {};
  }

  chrome.sockets.tcpServer.onAccept.addListener(function (info) {
    if (info.socketId !== serverSocketId) return;
    chrome.sockets.tcp.setPaused(info.clientSocketId, false);
    clients[info.clientSocketId] = {
      buf: new Uint8Array(0),
      body: new Uint8Array(0),
      headerDone: false,
      got: 0,
      contentLength: 0
    };
  });

  chrome.sockets.tcp.onReceive.addListener(onReceive);
  chrome.sockets.tcp.onReceiveError.addListener(function (info) {
    try { chrome.sockets.tcp.close(info.socketId); } catch (e) {}
    delete clients[info.socketId];
  });

  g.SpeakerServer = {
    start: start,
    stop: stop,
    setOnAudio: function (fn) { onAudio = fn; },
    setOnFrame: function (fn) { onFrame = fn; },
    setOnPcm: function (fn) { onPcm = fn; },
    setOnRegister: function (fn) { onRegister = fn; },
    setOnLog: function (fn) { onLog = fn || function () {}; },
    getPort: function () { return PORT; }
  };
})(this);
