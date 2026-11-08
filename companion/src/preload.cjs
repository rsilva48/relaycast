const { contextBridge, ipcRenderer } = require('electron');

// The only things the window can ask the companion to do.
contextBridge.exposeInMainWorld('companion', {
  state: () => ipcRenderer.invoke('state'),
  log: () => ipcRenderer.invoke('log'),
  addFolder: () => ipcRenderer.invoke('add-folder'),
  removeFolder: (folder) => ipcRenderer.invoke('remove-folder', folder),
  rescan: () => ipcRenderer.invoke('rescan'),
  restart: () => ipcRenderer.invoke('restart'),
  settings: (changes) => ipcRenderer.invoke('settings', changes),
  qr: (address, withAdmin) => ipcRenderer.invoke('qr', address, withAdmin),
  copy: (text) => ipcRenderer.invoke('copy', text),
  copyAdmin: () => ipcRenderer.invoke('copy-admin'),
  openLogs: () => ipcRenderer.invoke('open-logs'),
  openData: () => ipcRenderer.invoke('open-data'),
  catalogList: () => ipcRenderer.invoke('catalog-list'),
  catalogDetail: (id) => ipcRenderer.invoke('catalog-detail', id),
  catalogSave: (id, changes) => ipcRenderer.invoke('catalog-save', id, changes),
  catalogPoster: (id) => ipcRenderer.invoke('catalog-poster', id),
  catalogPosterClear: (id) => ipcRenderer.invoke('catalog-poster-clear', id),
  catalogMarks: (fileId) => ipcRenderer.invoke('catalog-marks', fileId),
  catalogArt: (id, version) => ipcRenderer.invoke('catalog-art', id, version),
  onChange: (callback) => ipcRenderer.on('changed', () => callback()),
});
