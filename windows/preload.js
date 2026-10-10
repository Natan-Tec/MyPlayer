'use strict';
const { contextBridge, ipcRenderer } = require('electron');

const call = (channel) => (arg) => ipcRenderer.invoke(channel, arg);

contextBridge.exposeInMainWorld('api', {
  info: call('app:info'),
  stateGet: call('state:get'),
  stateSet: call('state:set'),
  setStream: call('stream:set'),
  download: call('playlist:download'),
  cached: call('playlist:cached'),
  pickLocal: call('playlist:pickLocal'),
  saveLocal: call('playlist:saveLocal'),
  removeList: call('playlist:remove'),
  dropCache: call('playlist:dropCache'),
  loadLogos: call('logos:load'),
  storageUsage: call('storage:usage'),
  clearCache: call('cache:clear'),
  readClipboard: call('clipboard:read'),
  toggleFullscreen: call('win:toggleFullscreen'),
  isFullscreen: call('win:isFullscreen'),
  exitFullscreen: call('win:exitFullscreen'),
  keepAwake: call('power:keepAwake'),
});
