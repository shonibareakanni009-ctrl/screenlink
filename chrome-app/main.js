(function () {
  var $ = function (id) { return document.getElementById(id); };
  var power = false;
  var player = $('player');
  var audioCtx = null;
  var micStream = null;
  var micAnalyser = null;
  var playAnalyser = null;
  var raf = 0;
  var objectUrl = null;
  var serverOn = false;
  var localIps = [];
  var nextPcmTime = 0;
  var frameCount = 0;
  var controlUrl = '';
  var mirrorClickBound = false;

  function log(m) { $('log').textContent = m; }
  function setStatus(text, cls) {
    var s = $('status');
    s.textContent = text;
    s.className = 'pill ' + (cls || 'off');
  }
  function setLed(on) { $('led').className = 'led' + (on ? ' on' : ''); }
  function pulse(on) { $('cone').className = 'cone' + (on ? ' pulse' : ''); }

  function ensureAudioCtx() {
    if (!audioCtx) {
      var AC = window.AudioContext || window.webkitAudioContext;
      audioCtx = new AC();
    }
    if (audioCtx.state === 'suspended') audioCtx.resume();
    return audioCtx;
  }

  function draw() {
    var canvas = $('viz');
    var ctx = canvas.getContext('2d');
    var w = canvas.width, h = canvas.height;
    ctx.fillStyle = '#0f1115';
    ctx.fillRect(0, 0, w, h);
    var analyser = micAnalyser || playAnalyser;
    if (!analyser || !power) {
      ctx.fillStyle = '#27272a';
      for (var i = 0; i < 16; i++) ctx.fillRect(8 + i * 17, h / 2 - 2, 12, 4);
      raf = requestAnimationFrame(draw);
      return;
    }
    var bins = new Uint8Array(analyser.frequencyBinCount);
    analyser.getByteFrequencyData(bins);
    for (var j = 0; j < 16; j++) {
      var v = bins[Math.floor(j * bins.length / 16)] / 255;
      var bh2 = Math.max(3, v * (h - 8));
      ctx.fillStyle = micAnalyser ? '#fb923c' : '#60a5fa';
      ctx.fillRect(8 + j * 17, h - bh2 - 4, 12, bh2);
    }
    pulse(bins[4] > 40);
    raf = requestAnimationFrame(draw);
  }

  function setPower(on) {
    power = on;
    $('btnPower').textContent = on ? 'Power Off' : 'Power On';
    $('btnPower').className = on ? 'danger' : 'primary';
    $('btnServer').disabled = !on;
    setLed(on);
    if (on) {
      setStatus('On', 'on');
      ensureAudioCtx();
      log('ScreenLink on');
    } else {
      stopPlayback();
      stopMic();
      stopServer();
      $('mirrorPanel').style.display = 'none';
      setStatus('Off', 'off');
      log('ScreenLink off');
    }
  }

  function playBlob(blob, label) {
    stopPlayback(true);
    objectUrl = URL.createObjectURL(blob);
    player.src = objectUrl;
    player.volume = parseFloat($('vol').value);
    player.onplay = function () {
      setStatus('Playing', 'play');
      $('btnStop').disabled = false;
      try {
        var ctx = ensureAudioCtx();
        var src = ctx.createMediaElementSource(player);
        playAnalyser = ctx.createAnalyser();
        playAnalyser.fftSize = 64;
        src.connect(playAnalyser);
        playAnalyser.connect(ctx.destination);
      } catch (e) {}
    };
    player.onended = function () {
      setStatus(power ? 'On' : 'Off', power ? 'on' : 'off');
      $('btnStop').disabled = true;
    };
    player.onerror = function () { log('Play error'); };
    player.play().catch(function (e) { log('Play failed: ' + e.message); });
    log('Playing ' + (label || 'audio'));
  }

  function stopPlayback(keepPower) {
    try { player.pause(); } catch (e) {}
    player.removeAttribute('src');
    if (objectUrl) { URL.revokeObjectURL(objectUrl); objectUrl = null; }
    playAnalyser = null;
    $('btnStop').disabled = true;
    if (!keepPower && power) setStatus('On', 'on');
  }

  function startMic() {
    ensureAudioCtx();
    navigator.mediaDevices.getUserMedia({ audio: true }).then(function (stream) {
      micStream = stream;
      var ctx = ensureAudioCtx();
      var src = ctx.createMediaStreamSource(stream);
      micAnalyser = ctx.createAnalyser();
      micAnalyser.fftSize = 64;
      src.connect(micAnalyser);
      $('btnMic').textContent = 'Mic: On';
      setStatus('Mic', 'mic');
      log('Mic on');
    }).catch(function (e) { log('Mic error: ' + e.message); });
  }

  function stopMic() {
    if (micStream) {
      micStream.getTracks().forEach(function (t) { t.stop(); });
      micStream = null;
    }
    micAnalyser = null;
    $('btnMic').textContent = 'Mic: Off';
    if (power) setStatus('On', 'on');
  }

  function playPcmChunk(ab) {
    try {
      var ctx = ensureAudioCtx();
      var view = new DataView(ab);
      var samples = ab.byteLength / 2;
      if (samples < 1) return;
      var buffer = ctx.createBuffer(1, samples, 16000);
      var ch = buffer.getChannelData(0);
      for (var i = 0; i < samples; i++) ch[i] = view.getInt16(i * 2, true) / 32768;
      var src = ctx.createBufferSource();
      src.buffer = buffer;
      var gain = ctx.createGain();
      gain.gain.value = parseFloat($('vol').value);
      src.connect(gain);
      gain.connect(ctx.destination);
      var t = Math.max(ctx.currentTime + 0.02, nextPcmTime);
      src.start(t);
      nextPcmTime = t + buffer.duration;
      setStatus('Mirror', 'play');
    } catch (e) {}
  }

  function bindMirrorClick() {
    if (mirrorClickBound) return;
    mirrorClickBound = true;
    var img = $('mirror');
    img.style.cursor = 'crosshair';
    img.title = 'Click to tap on the phone';
    img.addEventListener('click', function (ev) {
      if (!controlUrl) {
        log('No phone control yet — enable Accessibility on phone');
        return;
      }
      var rect = img.getBoundingClientRect();
      var nx = Math.max(0, Math.min(1, (ev.clientX - rect.left) / rect.width));
      var ny = Math.max(0, Math.min(1, (ev.clientY - rect.top) / rect.height));
      var body = JSON.stringify({ x: nx, y: ny });
      var xh = new XMLHttpRequest();
      xh.open('POST', controlUrl + '/tap');
      xh.setRequestHeader('Content-Type', 'application/json');
      xh.onload = function () {
        log(xh.status === 200 ? ('Tap ' + nx.toFixed(2) + ',' + ny.toFixed(2)) : ('Tap failed: ' + xh.responseText));
      };
      xh.onerror = function () { log('Tap network error'); };
      xh.send(body);
    });
  }

  function showFrame(ab) {
    var blob = new Blob([ab], { type: 'image/jpeg' });
    var url = URL.createObjectURL(blob);
    var img = $('mirror');
    var old = img.getAttribute('data-url');
    img.onload = function () { if (old) URL.revokeObjectURL(old); };
    img.src = url;
    img.setAttribute('data-url', url);
    $('mirrorPanel').style.display = 'block';
    bindMirrorClick();
    frameCount++;
    if (frameCount % 10 === 1) {
      $('mirrorMeta').textContent = 'Live · frame ' + frameCount + ' · ' + Math.round(ab.byteLength / 1024) + ' KB';
    }
    setStatus('Mirror', 'play');
  }

  function refreshIps(cb) {
    localIps = [];
    if (!chrome.system || !chrome.system.network) {
      if (cb) cb();
      return;
    }
    chrome.system.network.getNetworkInterfaces(function (ifaces) {
      (ifaces || []).forEach(function (iface) {
        if (iface.address && iface.address.indexOf(':') < 0 && iface.address !== '127.0.0.1') {
          localIps.push(iface.address);
        }
      });
      if (cb) cb();
    });
  }

  function updateNetUi() {
    if (!power) {
      $('netInfo').textContent = 'Turn power on to start the receiver.';
      $('btnCopy').disabled = true;
      $('phoneHint').textContent = '';
      return;
    }
    var port = (typeof SpeakerServer !== 'undefined' && SpeakerServer.getPort) ? SpeakerServer.getPort() : 8080;
    if (!serverOn) {
      $('netInfo').textContent = 'Power on. Click Start Wi-Fi receiver.';
      $('btnCopy').disabled = true;
      $('phoneHint').textContent = '';
      return;
    }
    if (localIps.length === 0) {
      $('netInfo').textContent = 'Receiver on, but no Wi-Fi IP found. Connect to Wi-Fi.';
      $('btnCopy').disabled = true;
      $('phoneHint').textContent = 'http://CHROMEBOOK_IP:' + port;
      return;
    }
    var links = localIps.map(function (ip) { return 'http://' + ip + ':' + port; });
    $('netInfo').textContent = 'Receiver running. Paste this link in the phone app:';
    $('phoneHint').textContent = links.join('\n');
    $('btnCopy').disabled = false;
  }

  function startServer() {
    if (!power) return;
    refreshIps(function () {
      SpeakerServer.setOnLog(log);
      SpeakerServer.setOnAudio(function (ab, mime) {
        playBlob(new Blob([ab], { type: mime || 'audio/mpeg' }), 'from phone');
      });
      SpeakerServer.setOnFrame(function (ab) { showFrame(ab); });
      SpeakerServer.setOnPcm(function (ab) { playPcmChunk(ab); });
      SpeakerServer.setOnRegister(function (jsonStr) {
        try {
          var j = JSON.parse(jsonStr);
          if (j && j.control) {
            controlUrl = String(j.control).replace(/\/$/, '');
            log('Remote control ready: ' + controlUrl);
            $('mirrorMeta').textContent = 'Live · click image to control phone';
          }
        } catch (e) { log('Register parse error'); }
      });
      SpeakerServer.start(8080, function (ok) {
        if (!ok) {
          log('Could not start Wi-Fi server');
          serverOn = false;
        } else {
          serverOn = true;
          $('btnServer').textContent = 'Stop Wi-Fi receiver';
          log('Wi-Fi receiver ready');
        }
        updateNetUi();
      });
    });
  }

  function stopServer() {
    if (typeof SpeakerServer !== 'undefined') SpeakerServer.stop();
    serverOn = false;
    controlUrl = '';
    $('btnServer').textContent = 'Start Wi-Fi receiver';
    updateNetUi();
  }

  $('btnPower').onclick = function () { setPower(!power); updateNetUi(); };
  $('btnMic').onclick = function () { if (micStream) stopMic(); else startMic(); };
  $('btnPick').onclick = function () {
    var input = document.createElement('input');
    input.type = 'file';
    input.accept = 'audio/*,.mp3,.wav,.ogg,.m4a';
    input.onchange = function () {
      if (input.files && input.files[0]) playBlob(input.files[0], input.files[0].name);
    };
    input.click();
  };
  $('btnStop').onclick = function () { stopPlayback(); };
  $('vol').oninput = function () {
    player.volume = parseFloat(this.value);
    $('volPct').textContent = Math.round(this.value * 100) + '%';
  };
  $('btnServer').onclick = function () {
    if (serverOn) stopServer(); else startServer();
  };
  $('btnCopy').onclick = function () {
    var t = ($('phoneHint').textContent || '').split('\n')[0];
    if (!t) return;
    var ta = document.createElement('textarea');
    ta.value = t;
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); log('Copied ' + t); }
    catch (e) { log('Copy failed – select the link manually'); }
    document.body.removeChild(ta);
  };

  draw();
  updateNetUi();
})();
