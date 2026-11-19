/**
 * Native overlay search bridge. Uses existing Atlas auth token only at activation time.
 * The Java foreground service never persists the token to disk.
 */
import { Capacitor, registerPlugin } from '@capacitor/core';

export interface OverlayStatus {
  supported: boolean;
  permissionGranted: boolean;
  running: boolean;
}
interface AtlasOverlayBridge {
  status(): Promise<OverlayStatus>;
  requestPermission(): Promise<OverlayStatus>;
  start(options: { token: string }): Promise<OverlayStatus>;
  stop(): Promise<OverlayStatus>;
}

const plugin = registerPlugin<AtlasOverlayBridge>('AtlasOverlay');

export const atlasOverlay = {
  available: () => Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android',
  status: () => plugin.status(),
  requestPermission: () => plugin.requestPermission(),
  start: (token: string) => plugin.start({ token }),
  stop: () => plugin.stop(),
};
