chrome.app.runtime.onLaunched.addListener(function () {
  chrome.app.window.create('main.html', {
    id: 'screenlink',
    outerBounds: { width: 900, height: 640, minWidth: 640, minHeight: 480 }
  });
});
