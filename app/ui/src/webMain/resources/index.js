// Makes the app installable and keeps its own files for a flaky network; see sw.js.
if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js');
