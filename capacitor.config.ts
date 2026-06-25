import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'app.lovable.c5e77d46f3cc41f282e2a927be029e53',
  appName: 'AlphaBot MVP',
  webDir: 'dist',
  // O app é empacotado a partir do build local (webDir 'dist'), e NÃO carregado
  // de uma URL remota. Isso faz o app abrir sem internet — essencial para o
  // controle local (Wi-Fi/Hotspot) do robô. Para preview no Lovable, reative
  // temporariamente o bloco `server.url`.
};

export default config;
